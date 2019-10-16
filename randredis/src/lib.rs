use std::ffi::{CStr, CString};

mod ffi;

#[no_mangle]
pub unsafe extern "C" fn RandGen_RedisCommand(
    ctx: *mut ffi::RedisModuleCtx,
    _argv: *mut *mut ffi::RedisModuleString,
    _argc: libc::c_int,
)-> libc::c_int{
    ffi::RedisModule_ReplyWithLongLong.unwrap()(ctx,0);
    ffi::REDIS_OK
}

#[no_mangle]
pub unsafe extern "C" fn RedisModule_OnLoad(
    ctx: *mut ffi::RedisModuleCtx,
    _argv: *mut *mut ffi::RedisModuleString,
    _argc: libc::c_int,
    )-> libc::c_int{
        if ffi::ExportedRedisModule_Init(ctx, "rrand\0".as_ptr(), 1 , ffi::APIVER_1)==1{
            return ffi::REDIS_ERR;
        }

        let write_flag = CString::new("write").unwrap();
        let gen_cmd = CString::new("rand.gen").unwrap();

        if ffi::RedisModule_CreateCommand.unwrap()(
            ctx,
            gen_cmd.as_ptr(),
            Some(RandGen_RedisCommand),
            write_flag.as_ptr(),
            1,1,1) == ffi::REDIS_ERR{
                return ffi::REDIS_ERR;
        }
        ffi::REDIS_OK
}
