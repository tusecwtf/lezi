use rusqlite::{Connection, OpenFlags};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

/// Open a frozen SQLite copy without creating WAL/SHM sidecars.
///
/// Filesystem bytes are percent-encoded so URI delimiters and non-UTF-8 names
/// cannot change which database SQLite opens.
pub(crate) fn open_immutable(path: &Path) -> rusqlite::Result<Connection> {
    let mut uri = String::from("file:");
    for &byte in path.as_os_str().as_bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'/' | b'-' | b'.' | b'_') {
            uri.push(char::from(byte));
        } else {
            use std::fmt::Write as _;
            write!(&mut uri, "%{byte:02X}").expect("writing to String cannot fail");
        }
    }
    uri.push_str("?immutable=1");
    Connection::open_with_flags(
        uri,
        OpenFlags::SQLITE_OPEN_READ_ONLY
            | OpenFlags::SQLITE_OPEN_NO_MUTEX
            | OpenFlags::SQLITE_OPEN_URI,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;
    use std::os::unix::ffi::OsStringExt;
    use tempfile::tempdir;

    #[test]
    fn immutable_open_handles_uri_delimiters_and_non_utf8_path_bytes() {
        let temp = tempdir().unwrap();
        let directory = temp
            .path()
            .join(OsString::from_vec(b"reserved?#%\xFF".to_vec()));
        std::fs::create_dir(&directory).unwrap();
        let database = directory.join("lezi.db");
        let writable = Connection::open(&database).unwrap();
        writable
            .execute_batch("CREATE TABLE marker(value INTEGER); INSERT INTO marker VALUES (7);")
            .unwrap();
        drop(writable);

        let immutable = open_immutable(&database).unwrap();

        assert_eq!(
            immutable
                .query_row("SELECT value FROM marker", [], |row| row.get::<_, i64>(0))
                .unwrap(),
            7,
        );
    }
}
