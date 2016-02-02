package kvstore

import akka.actor._
import kvstore.Arbiter._
import org.scalatest.time.Second
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import scala.collection.mutable
import scala.concurrent.duration._
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */
  
  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  arbiter ! Join
  val persistance = context.system.actorOf(persistenceProps)

  var persistMap = Map.empty[Long, (ActorRef,Cancellable)]
  var replicaAckMap = Map.empty[Long, (ActorRef,Long)]
  var replicatorAckMap = new mutable.HashMap[ActorRef, mutable.Set[Long]] with mutable.MultiMap[ActorRef,Long]


  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  def handleReplicate(id: Long, msg:Replicate) {
    println("replicates"+replicators.size+replicators.contains(self) + msg.key)

    if (!replicators.isEmpty) {
      replicaAckMap += id ->(sender, replicators.size)
      replicators foreach {
        r => replicatorAckMap.addBinding(r, id)
          r ! msg
      }
    }
  }
  def HandleFailures(id: Long) {
    context.system.scheduler.scheduleOnce(1 second) {
      persistMap.get(id) match {
        case Some((a, c)) => {
          c.cancel()
          println("failed persistance" + id)
          a ! OperationFailed(id)
          persistMap -= id
        }
        case None => {
          replicaAckMap.get(id) match {
            case Some((a, l)) => {
              println("failed replication" + id + l)
              replicaAckMap -= id
              a ! OperationFailed(id)
            }
            case None =>
          }

        }
      }
    }
  }
  var ids =0
  /* TODO Behavior for  the leader role. */
  val leader: Receive = {
    case Insert(key,vl,id) =>{
      kv = kv.+(key->vl)

      handleReplicate(id,Replicate(key, Some(vl),id))

      persistMap+=id -> (sender,
        context.system.scheduler.schedule(0 milliseconds, 100 milliseconds,
          persistance, Persist(key, Some(vl), id)))

      HandleFailures(id)

    }
    case Remove(key,id) => {
      kv = kv - key

      handleReplicate(id,Replicate(key,None,id))

      persistMap+=id -> (sender,
        context.system.scheduler.schedule(0 milliseconds, 100 milliseconds,
          persistance, Persist(key, None, id)))

      HandleFailures(id)

    }
    case Get(key,id) => {
      val res: Option[String] = kv.get(key)
      sender ! GetResult(key, res, id)
    }

    case Replicated(key,id) => {
      println("replicated" + id)
      replicatorAckMap.get(sender) match {
       case Some(c) => {
          c-=id
        }
       case None =>
      }

      replicaAckMap.get(id) match {

        case Some((a,l)) if l > 1 => {
          replicaAckMap += id-> (a,l-1)
          println("acked" + id + l)
        }
        case Some((a,1)) => {
          println("allacked" + id)
          replicaAckMap-=id
          if(! persistMap.contains(id)){
            a ! OperationAck(id)
          }
        }
        case None =>
      }

    }

    case Persisted(key,id)=>{
      persistMap.get(id) match {
        case Some((a, c)) => {
          println("persisted" + id)
          c.cancel()
          persistMap -= id
          if(! replicaAckMap.contains(id)){
            a ! OperationAck(id)
          }
        }
        case None =>
      }
    }



    case Replicas(s)=> {
      val newsecondaries = s -self;
      val joined = newsecondaries -- secondaries.keySet
      val left = secondaries.keySet -- s
      joined foreach {
        r  => 
          val rr = context.system.actorOf(Replicator.props(r))
          secondaries+=r->rr
          replicators+=rr
          kv foreach {e => { rr ! Replicate(e._1,Some(e._2),ids)  } }
          ids+=1
        
      }
      left foreach {
        r  => 
          secondaries.get(r) match {
            case Some(ror) => {
              context.stop(r)
              context.stop(ror)
              secondaries -= r
              replicatorAckMap get ror match {
                case Some(outstandingAcks) => {
                  outstandingAcks foreach { a =>
                    self ! Replicated("", a)
                  }
                  replicatorAckMap -= ror
                }
                case None =>
              }
            }
            case None =>
          }
      }
    }
  }


  var expected = 0
  var secPersistAckMap = Map.empty[Long, (ActorRef,String,Cancellable)]
  /* TODO Behavior for the replica role. */
  val replica: Receive = {

    case Get(key,id) => {
      val res: Option[String] = kv.get(key)
      sender ! GetResult(key, res, id)
    }

    case Snapshot(key,vl,seq) => {
      if(seq<expected){
        sender ! SnapshotAck(key,seq)
      } else if (seq == expected){
        expected += 1
        vl match {
          case Some(value) => {
            kv += key -> value
            secPersistAckMap += seq -> (sender, key, context.system.scheduler.schedule(0 milliseconds, 100 milliseconds, persistance, Persist(key, Some(value), seq)))
          }
          case None => {
            kv -= key
            secPersistAckMap += seq -> (sender, key, context.system.scheduler.schedule(0 milliseconds, 100 milliseconds, persistance, Persist(key, None, seq)))
          }
        }
      }
    }

    case Persisted(key,id) => {
      secPersistAckMap.get(id) match {
        case Some((a,key,c))=>
          println("persisted")
          c.cancel()
          secPersistAckMap-=id
          a ! SnapshotAck(key,id)
        case None =>
      }
    }


  }

}

