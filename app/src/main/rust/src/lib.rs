use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jlongArray, jstring};
use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

// ═══════════════════════════════════════════════════════════════
// 1. DIRECTORY SCAN — readdir + stat in one native call
// ═══════════════════════════════════════════════════════════════

struct DirResult {
    sizes: Vec<i64>,
    mtimes: Vec<i64>,
    flags: Vec<i32>,
    name_offsets: Vec<i32>,
    name_lens: Vec<i32>,
    names_buf: Vec<u8>,
}

static mut SLOTS: [Option<DirResult>; 4] = [None, None, None, None];

fn alloc_slot() -> Option<usize> {
    for i in 0..4 {
        // SAFETY: JNI calls are single-threaded per thread; Android JNI
        // typically calls from the same thread for a given View.
        let slot = unsafe { &mut SLOTS[i] };
        if slot.is_none() {
            *slot = Some(DirResult {
                sizes: Vec::with_capacity(256),
                mtimes: Vec::with_capacity(256),
                flags: Vec::with_capacity(256),
                name_offsets: Vec::with_capacity(256),
                name_lens: Vec::with_capacity(256),
                names_buf: Vec::with_capacity(65536),
            });
            return Some(i);
        }
    }
    None
}

fn free_slot(s: usize) {
    if s < 4 {
        unsafe { SLOTS[s] = None; }
    }
}

fn get_slot(s: usize) -> Option<&'static DirResult> {
    if s < 4 {
        unsafe { SLOTS[s].as_ref() }
    } else {
        None
    }
}

// ═══════════════════════════════════════════════════════════════
// JNI: nativeScanDir(path, showHidden) -> slot index
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeScanDir(
    mut env: JNIEnv,
    _cls: JClass,
    jpath: JString,
    show_hidden: jboolean,
) -> jint {
    let path_str: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let path = Path::new(&path_str);

    let dir = match fs::read_dir(path) {
        Ok(d) => d,
        Err(_) => return -1,
    };

    let slot_idx = match alloc_slot() {
        Some(i) => i,
        None => return -1,
    };

    let dr = unsafe { SLOTS[slot_idx].as_mut().unwrap() };
    let show = show_hidden != 0;

    for entry in dir {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.file_name();
        let name_bytes = name.as_bytes();
        let name_str = match std::str::from_utf8(name_bytes) {
            Ok(s) => s,
            Err(_) => continue,
        };

        // Skip . and ..
        if name_str == "." || name_str == ".." {
            continue;
        }

        let is_hidden = name_bytes.first() == Some(&b'.');
        if !show && is_hidden {
            continue;
        }

        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => {
                // Try lstat via symlink_metadata
                match fs::symlink_metadata(entry.path()) {
                    Ok(m) => m,
                    Err(_) => continue,
                }
            }
        };

        let size = metadata.len() as i64;
        let mtime = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        let is_dir = metadata.is_dir() as i32;

        // Permissions (user bits)
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = metadata.permissions().mode();
            let cr = ((mode & 0o400) != 0) as i32;
            let cw = ((mode & 0o200) != 0) as i32;
            let cx = ((mode & 0o100) != 0) as i32;
            let flag = is_dir | (is_hidden as i32) << 1 | cr << 2 | cw << 3 | cx << 4;

            dr.name_offsets.push(dr.names_buf.len() as i32);
            dr.name_lens.push(name_bytes.len() as i32);
            dr.names_buf.extend_from_slice(name_bytes);
            dr.names_buf.push(0); // null terminator
            dr.sizes.push(size);
            dr.mtimes.push(mtime);
            dr.flags.push(flag);
        }
    }

    slot_idx as jint
}

// ═══════════════════════════════════════════════════════════════
// JNI: getter functions for slot data
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetCount(
    _env: JNIEnv, _cls: JClass, slot: jint,
) -> jint {
    get_slot(slot as usize).map_or(0, |d| d.sizes.len() as jint)
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetSizes(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jlongArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_long_array(dr.sizes.len() as i32) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &dr.sizes);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetMtimes(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jlongArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_long_array(dr.mtimes.len() as i32) {
        Ok(arr) => {
            let _ = env.set_long_array_region(&arr, 0, &dr.mtimes);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetFlags(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jintArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_int_array(dr.flags.len() as i32) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, &dr.flags);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetNames(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jbyteArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_byte_array(dr.names_buf.len() as i32) {
        Ok(arr) => {
            let signed: &[i8] = unsafe { std::slice::from_raw_parts(dr.names_buf.as_ptr() as *const i8, dr.names_buf.len()) };
            let _ = env.set_byte_array_region(&arr, 0, signed);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetNameOffsets(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jintArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_int_array(dr.name_offsets.len() as i32) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, &dr.name_offsets);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetNameLens(
    mut env: JNIEnv, _cls: JClass, slot: jint,
) -> jintArray {
    let dr = match get_slot(slot as usize) {
        Some(d) => d,
        None => return std::ptr::null_mut(),
    };
    match env.new_int_array(dr.name_lens.len() as i32) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, &dr.name_lens);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeFreeSlot(
    _env: JNIEnv, _cls: JClass, slot: jint,
) {
    free_slot(slot as usize);
}

// ═══════════════════════════════════════════════════════════════
// 2. FILE OPERATIONS
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeIsEmptyDir(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jboolean {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 1, // treat error as empty
    };
    match fs::read_dir(&path) {
        Ok(mut dir) => {
            if dir.all(|e| {
                e.ok().map_or(true, |e| {
                    let n = e.file_name();
                    n == "." || n == ".."
                })
            }) {
                1
            } else {
                0
            }
        }
        Err(_) => 1,
    }
}

// ═══════════════════════════════════════════════════════════════
// 3. FILE COPY — sendfile() loop, zero userspace buffer copies
// ═══════════════════════════════════════════════════════════════

fn copy_file(src: &Path, dst: &Path) -> Result<(), i32> {
    let metadata = fs::metadata(src).map_err(|_| -1i32)?;
    let mode = {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            metadata.permissions().mode() & 0o777
        }
        #[cfg(not(unix))]
        { 0o644u32 }
    };

    // Use std::fs::copy for portability; it uses sendfile internally on Linux
    fs::copy(src, dst).map_err(|_| -1i32)?;

    // Preserve permissions
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(dst, fs::Permissions::from_mode(mode));
    }

    // Preserve mtime
    if let Ok(mtime) = metadata.modified() {
        let _ = filetime::set_file_mtime(dst, filetime::FileTime::from_system_time(mtime));
    }

    Ok(())
}

fn copy_symlink(src: &Path, dst: &Path) -> Result<(), i32> {
    let target = fs::read_link(src).map_err(|_| -1i32)?;
    std::os::unix::fs::symlink(target, dst).map_err(|_| -1i32)
}

fn copy_entry(src: &Path, dst: &Path) -> Result<(), i32> {
    let metadata = fs::symlink_metadata(src).map_err(|_| -1i32)?;
    let file_type = metadata.file_type();

    if file_type.is_dir() {
        copy_dir_recursive(src, dst)
    } else if file_type.is_file() {
        copy_file(src, dst)
    } else if file_type.is_symlink() {
        copy_symlink(src, dst)
    } else {
        Ok(()) // skip special files
    }
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<(), i32> {
    let metadata = fs::metadata(src).map_err(|_| -1i32)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        use std::os::unix::fs::DirBuilderExt;
        let mut builder = fs::DirBuilder::new();
        builder.mode(metadata.permissions().mode() & 0o777);
        let _ = builder.create(dst);
    }
    #[cfg(not(unix))]
    {
        let _ = fs::create_dir(dst);
    }

    let dir = fs::read_dir(src).map_err(|_| -1i32)?;
    let mut ret = Ok(());

    for entry in dir {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.file_name();
        let child_src = src.join(&name);
        let child_dst = dst.join(&name);
        if copy_entry(&child_src, &child_dst).is_err() {
            ret = Err(-1);
        }
    }

    ret
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeCopy(
    mut env: JNIEnv, _cls: JClass, jsrc: JString, jdst: JString,
) -> jint {
    let src: String = match env.get_string(&jsrc) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let dst: String = match env.get_string(&jdst) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let src_path = Path::new(&src);
    let dst_path = Path::new(&dst);

    let metadata = match fs::metadata(src_path) {
        Ok(m) => m,
        Err(_) => return -1,
    };

    if metadata.is_dir() {
        copy_dir_recursive(src_path, dst_path).err().unwrap_or(0)
    } else {
        copy_file(src_path, dst_path).err().unwrap_or(0)
    }
}

// ═══════════════════════════════════════════════════════════════
// 4. RECURSIVE DELETE
// ═══════════════════════════════════════════════════════════════

fn remove_recursive(path: &Path) -> Result<(), i32> {
    let metadata = fs::symlink_metadata(path).map_err(|_| -1i32)?;

    if !metadata.is_dir() {
        return fs::remove_file(path).map_err(|_| -1i32);
    }

    let dir = fs::read_dir(path).map_err(|_| -1i32)?;
    let mut ret = Ok(());

    for entry in dir {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let child = entry.path();
        if remove_recursive(&child).is_err() {
            ret = Err(-1);
        }
    }

    fs::remove_dir(path).map_err(|_| -1i32)?;
    ret
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeDelete(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    remove_recursive(Path::new(&path)).err().unwrap_or(0)
}

// ═══════════════════════════════════════════════════════════════
// 5. RENAME / MKDIR
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeRename(
    mut env: JNIEnv, _cls: JClass, jold: JString, jnew: JString,
) -> jint {
    let old: String = match env.get_string(&jold) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let new: String = match env.get_string(&jnew) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    if fs::rename(&old, &new).is_ok() { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeMkdir(
    mut env: JNIEnv, _cls: JClass, jpath: JString, mode: jint,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        let mut builder = fs::DirBuilder::new();
        builder.mode(mode as u32);
        if builder.create(&path).is_ok() { 0 } else { -1 }
    }
    #[cfg(not(unix))]
    {
        if fs::create_dir(&path).is_ok() { 0 } else { -1 }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeMkdirs(
    mut env: JNIEnv, _cls: JClass, jpath: JString, mode: jint,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        let mut builder = fs::DirBuilder::new();
        builder.mode(mode as u32).recursive(true);
        if builder.create(&path).is_ok() { 0 } else { -1 }
    }
    #[cfg(not(unix))]
    {
        if fs::create_dir_all(&path).is_ok() { 0 } else { -1 }
    }
}

// ═══════════════════════════════════════════════════════════════
// 6. FILE EXISTS / IS_DIR
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeExists(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jboolean {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if Path::new(&path).exists() { 1 } else { 0 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeIsDir(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jboolean {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    if Path::new(&path).is_dir() { 1 } else { 0 }
}

// ═══════════════════════════════════════════════════════════════
// 7. RECURSIVE SEARCH
// ═══════════════════════════════════════════════════════════════

const SEARCH_MAX: usize = 200;
static mut SEARCH_RESULTS: Vec<String> = Vec::new();

fn search_recursive(dir_path: &Path, query: &str, qlen: usize) {
    let results = unsafe { &mut SEARCH_RESULTS };
    if results.len() >= SEARCH_MAX {
        return;
    }

    let dir = match fs::read_dir(dir_path) {
        Ok(d) => d,
        Err(_) => return,
    };

    let query_lower = query.to_lowercase();

    for entry in dir {
        if unsafe { SEARCH_RESULTS.len() } >= SEARCH_MAX {
            return;
        }
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        let name = entry.file_name();
        let name_str = match name.to_str() {
            Some(s) => s,
            None => continue,
        };

        if name_str == "." || name_str == ".." {
            continue;
        }

        // Case-insensitive substring match
        if name_str.len() >= qlen && name_str.to_lowercase().contains(&query_lower) {
            let full_path = dir_path.join(&name);
            unsafe { SEARCH_RESULTS.push(full_path.to_string_lossy().into_owned()); }
        }

        // Recurse into directories
        let child_meta = fs::symlink_metadata(entry.path());
        if let Ok(meta) = child_meta {
            if meta.is_dir() {
                search_recursive(&entry.path(), query, qlen);
            }
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeSearch(
    mut env: JNIEnv, _cls: JClass, jpath: JString, jquery: JString,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let query: String = match env.get_string(&jquery) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    unsafe { SEARCH_RESULTS.clear(); }
    search_recursive(Path::new(&path), &query, query.len());
    unsafe { SEARCH_RESULTS.len() as jint }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeGetSearchResult(
    mut env: JNIEnv, _cls: JClass, idx: jint,
) -> jstring {
    let results = unsafe { &SEARCH_RESULTS };
    if idx < 0 || idx as usize >= results.len() {
        return std::ptr::null_mut();
    }
    match env.new_string(&results[idx as usize]) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ═══════════════════════════════════════════════════════════════
// 8. RECURSIVE DISK USAGE
// ═══════════════════════════════════════════════════════════════

fn du_recursive(path: &Path) -> i64 {
    let metadata = match fs::symlink_metadata(path) {
        Ok(m) => m,
        Err(_) => return 0,
    };

    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let mut total = metadata.blocks() as i64 * 512;

        if metadata.is_dir() {
            if let Ok(dir) = fs::read_dir(path) {
                for entry in dir.flatten() {
                    total += du_recursive(&entry.path());
                }
            }
        }
        total
    }
    #[cfg(not(unix))]
    {
        let mut total = metadata.len() as i64;
        if metadata.is_dir() {
            if let Ok(dir) = fs::read_dir(path) {
                for entry in dir.flatten() {
                    total += du_recursive(&entry.path());
                }
            }
        }
        total
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeDiskUsage(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jlong {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    du_recursive(Path::new(&path))
}

// ═══════════════════════════════════════════════════════════════
// 9. TOUCH
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTouch(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let now = filetime::FileTime::now();
    if filetime::set_file_mtime(&path, now).is_ok() { 0 } else { -1 }
}

// ═══════════════════════════════════════════════════════════════
// 10. CHMOD / CHOWN / SYMLINK / SET MODTIME / READLINK / STAT MODE
// ═══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeChmod(
    mut env: JNIEnv, _cls: JClass, jpath: JString, mode: jint,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if fs::set_permissions(&path, fs::Permissions::from_mode(mode as u32)).is_ok() { 0 } else { -1 }
    }
    #[cfg(not(unix))]
    { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeChown(
    mut env: JNIEnv, _cls: JClass, jpath: JString, uid: jint, gid: jint,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let c_path = match std::ffi::CString::new(path.as_str()) {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let ret = unsafe { libc::chown(c_path.as_ptr(), uid as libc::uid_t, gid as libc::gid_t) };
    if ret == 0 { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeSymlink(
    mut env: JNIEnv, _cls: JClass, jtarget: JString, jlink: JString,
) -> jint {
    let target: String = match env.get_string(&jtarget) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let link: String = match env.get_string(&jlink) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    if std::os::unix::fs::symlink(&target, &link).is_ok() { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeSetModTime(
    mut env: JNIEnv, _cls: JClass, jpath: JString, millis: jlong,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let secs = (millis / 1000) as i64;
    let nsecs = ((millis % 1000) * 1_000_000) as u32;
    let mtime = filetime::FileTime::from_unix_time(secs, nsecs);
    let atime = mtime;
    if filetime::set_file_times(&path, atime, mtime).is_ok() { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeReadlink(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jstring {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    match fs::read_link(&path) {
        Ok(target) => match env.new_string(target.to_string_lossy()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeStatMode(
    mut env: JNIEnv, _cls: JClass, jpath: JString,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        match fs::symlink_metadata(&path) {
            Ok(meta) => meta.mode() as jint,
            Err(_) => -1,
        }
    }
    #[cfg(not(unix))]
    { -1 }
}

// ═══════════════════════════════════════════════════════════════
// 11. TEXT ENGINE — mmap-based file reading, line indexing,
//     search, and write-back for the virtualized text editor
// ═══════════════════════════════════════════════════════════════

use std::io::{Write, BufWriter};

struct TextBuffer {
    data: Vec<u8>,
    line_offsets: Vec<u32>,  // byte offset of each line start
    path: String,
}

const MAX_TEXT_BUFFERS: usize = 8;
static mut TEXT_BUFFERS: [Option<TextBuffer>; MAX_TEXT_BUFFERS] = [
    None, None, None, None, None, None, None, None,
];

fn alloc_text_slot() -> Option<usize> {
    for i in 0..MAX_TEXT_BUFFERS {
        if unsafe { TEXT_BUFFERS[i].is_none() } {
            return Some(i);
        }
    }
    None
}

fn get_text_buf(idx: usize) -> Option<&'static TextBuffer> {
    if idx < MAX_TEXT_BUFFERS {
        unsafe { TEXT_BUFFERS[idx].as_ref() }
    } else {
        None
    }
}

fn get_text_buf_mut(idx: usize) -> Option<&'static mut TextBuffer> {
    if idx < MAX_TEXT_BUFFERS {
        unsafe { TEXT_BUFFERS[idx].as_mut() }
    } else {
        None
    }
}

fn build_line_offsets(data: &[u8]) -> Vec<u32> {
    let mut offsets = Vec::with_capacity(data.len() / 40);
    offsets.push(0);
    for (i, &b) in data.iter().enumerate() {
        if b == b'\n' && i + 1 < data.len() {
            offsets.push((i + 1) as u32);
        }
    }
    offsets
}

/// Open a text file, read into memory, build line index.
/// Returns a handle (>= 0) or -1 on error.
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextOpen(
    mut env: JNIEnv,
    _cls: JClass,
    jpath: JString,
) -> jint {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let data = match std::fs::read(&path) {
        Ok(d) => d,
        Err(_) => return -1,
    };
    let line_offsets = build_line_offsets(&data);
    let slot = match alloc_text_slot() {
        Some(s) => s,
        None => return -1,
    };
    unsafe {
        TEXT_BUFFERS[slot] = Some(TextBuffer {
            data,
            line_offsets,
            path,
        });
    }
    slot as jint
}

/// Return total line count.
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextLineCount(
    _env: JNIEnv,
    _cls: JClass,
    handle: jint,
) -> jint {
    match get_text_buf(handle as usize) {
        Some(tb) => tb.line_offsets.len() as jint,
        None => 0,
    }
}

/// Return a single line as a Java string (0-indexed).
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextGetLine(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jint,
    line_num: jint,
) -> jstring {
    let tb = match get_text_buf(handle as usize) {
        Some(b) => b,
        None => return std::ptr::null_mut(),
    };
    let idx = line_num as usize;
    if idx >= tb.line_offsets.len() {
        return std::ptr::null_mut();
    }
    let start = tb.line_offsets[idx] as usize;
    let end = if idx + 1 < tb.line_offsets.len() {
        // Strip trailing \n
        let raw_end = tb.line_offsets[idx + 1] as usize;
        if raw_end > start && tb.data[raw_end - 1] == b'\n' {
            raw_end - 1
        } else {
            raw_end
        }
    } else {
        tb.data.len()
    };
    let line_bytes = &tb.data[start..end];
    let line_str = std::str::from_utf8(line_bytes).unwrap_or("");
    match env.new_string(line_str) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return the byte length of a line (for scroll position calculation).
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextLineByteLen(
    _env: JNIEnv,
    _cls: JClass,
    handle: jint,
    line_num: jint,
) -> jint {
    let tb = match get_text_buf(handle as usize) {
        Some(b) => b,
        None => return 0,
    };
    let idx = line_num as usize;
    if idx >= tb.line_offsets.len() {
        return 0;
    }
    let start = tb.line_offsets[idx] as usize;
    let end = if idx + 1 < tb.line_offsets.len() {
        tb.line_offsets[idx + 1] as usize
    } else {
        tb.data.len()
    };
    (end - start) as jint
}

/// Search for a string starting from a given line. Returns the line number or -1.
/// direction: 1 = forward, -1 = backward
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextSearch(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jint,
    jquery: JString,
    from_line: jint,
    direction: jint,
    case_sensitive: jboolean,
) -> jint {
    let tb = match get_text_buf(handle as usize) {
        Some(b) => b,
        None => return -1,
    };
    let query: String = match env.get_string(&jquery) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    if query.is_empty() {
        return -1;
    }
    let line_count = tb.line_offsets.len() as i32;
    let query_lower = if case_sensitive == 0 { query.to_lowercase() } else { query.clone() };

    if direction >= 0 {
        // Forward search
        let start = if from_line < 0 { 0 } else { from_line };
        for i in start..line_count {
            let line_start = tb.line_offsets[i as usize] as usize;
            let line_end = if (i as usize + 1) < tb.line_offsets.len() {
                tb.line_offsets[i as usize + 1] as usize
            } else {
                tb.data.len()
            };
            let line = std::str::from_utf8(&tb.data[line_start..line_end]).unwrap_or("");
            let haystack = if case_sensitive == 0 { line.to_lowercase() } else { line.to_string() };
            if haystack.contains(&query_lower) {
                return i;
            }
        }
    } else {
        // Backward search
        let start = if from_line < 0 { line_count - 1 } else { from_line.min(line_count - 1) };
        for i in (0..=start).rev() {
            let line_start = tb.line_offsets[i as usize] as usize;
            let line_end = if (i as usize + 1) < tb.line_offsets.len() {
                tb.line_offsets[i as usize + 1] as usize
            } else {
                tb.data.len()
            };
            let line = std::str::from_utf8(&tb.data[line_start..line_end]).unwrap_or("");
            let haystack = if case_sensitive == 0 { line.to_lowercase() } else { line.to_string() };
            if haystack.contains(&query_lower) {
                return i;
            }
        }
    }
    -1
}

/// Update the content of a text buffer (after editing in Kotlin).
/// This replaces the entire buffer content and rebuilds line offsets.
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextSetContent(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jint,
    jcontent: JString,
) -> jboolean {
    let content: String = match env.get_string(&jcontent) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let tb = match get_text_buf_mut(handle as usize) {
        Some(b) => b,
        None => return 0,
    };
    tb.data = content.into_bytes();
    tb.line_offsets = build_line_offsets(&tb.data);
    1
}

/// Save the buffer content to the original file path.
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextSave(
    _env: JNIEnv,
    _cls: JClass,
    handle: jint,
) -> jboolean {
    let tb = match get_text_buf(handle as usize) {
        Some(b) => b,
        None => return 0,
    };
    let file = match std::fs::File::create(&tb.path) {
        Ok(f) => f,
        Err(_) => return 0,
    };
    let mut writer = BufWriter::new(file);
    if writer.write_all(&tb.data).is_ok() && writer.flush().is_ok() {
        1
    } else {
        0
    }
}

/// Save buffer content to a different path (Save As).
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextSaveAs(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jint,
    jpath: JString,
) -> jboolean {
    let path: String = match env.get_string(&jpath) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let tb = match get_text_buf(handle as usize) {
        Some(b) => b,
        None => return 0,
    };
    let file = match std::fs::File::create(&path) {
        Ok(f) => f,
        Err(_) => return 0,
    };
    let mut writer = BufWriter::new(file);
    if writer.write_all(&tb.data).is_ok() && writer.flush().is_ok() {
        1
    } else {
        0
    }
}

/// Close a text buffer, freeing its memory.
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextClose(
    _env: JNIEnv,
    _cls: JClass,
    handle: jint,
) {
    let idx = handle as usize;
    if idx < MAX_TEXT_BUFFERS {
        unsafe { TEXT_BUFFERS[idx] = None; }
    }
}

/// Get total byte size of the buffer (for file size display).
#[no_mangle]
pub extern "C" fn Java_com_twopane_fm_util_NativeFileOps_nativeTextSize(
    _env: JNIEnv,
    _cls: JClass,
    handle: jint,
) -> jlong {
    match get_text_buf(handle as usize) {
        Some(tb) => tb.data.len() as jlong,
        None => 0,
    }
}
