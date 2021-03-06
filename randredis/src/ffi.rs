#![allow(non_upper_case_globals)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]

include!(concat!(env!("OUT_DIR"), "/bindings.rs"));

pub const ERRORMSG_WRONGTYPE: &str =
    "WRONGTYPE Operation against a key holding the wrong kind of value";
pub const SIMPLE_OK: &str = "OK";
pub const APIVER_1: libc::c_int = 1;
pub const REDIS_OK: libc::c_int = 0;
pub const REDIS_ERR: libc::c_int = 1;
pub const POSTPONED_ARRAY_LEN: libc::c_long = -1;

#[allow(improper_ctypes)]
extern "C" {
    pub fn ExportedRedisModule_Init(
        ctx: *mut RedisModuleCtx,
        module_name: *const u8,
        module_version: libc::c_int,
        api_version: libc::c_int,
    ) -> libc::c_int;
}
