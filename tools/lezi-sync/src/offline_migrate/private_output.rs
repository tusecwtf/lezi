//! Invocation-owned offline copy-out resources. Never adopt or delete a prior
//! invocation's paths, including historical fixed-name staging directories.
use std::fs::{self, File, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};

use uuid::Uuid;

pub(super) fn private_directories(path: &Path) -> io::Result<()> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(path)
}

pub(super) fn private_file(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

pub(super) fn copy_private(source: &Path, dest: &Path) -> io::Result<()> {
    let mut input = File::open(source)?;
    let mut output = private_file(dest)?;
    io::copy(&mut input, &mut output)?;
    output.sync_all()
}

/// Resolve existing ancestors before comparing paths. In particular, a lexical
/// sibling reached through a symlink must not become an output inside input.
pub(super) fn physical_path(path: &Path) -> io::Result<PathBuf> {
    match fs::canonicalize(path) {
        Ok(path) => Ok(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            let name = path.file_name().ok_or(error)?;
            let parent = path
                .parent()
                .filter(|p| !p.as_os_str().is_empty())
                .unwrap_or(Path::new("."));
            Ok(physical_path(parent)?.join(name))
        }
        Err(error) => Err(error),
    }
}

pub(super) fn disjoint_paths(source: &Path, dest: &Path) -> io::Result<(PathBuf, PathBuf)> {
    let source = fs::canonicalize(source)?;
    let dest = physical_path(dest)?;
    if source.starts_with(&dest) || dest.starts_with(&source) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "offline migration input and output must be disjoint physical paths",
        ));
    }
    Ok((source, dest))
}

pub(super) struct PrivateDirectory {
    path: PathBuf,
}

impl PrivateDirectory {
    pub(super) fn new(parent: &Path, prefix: &str) -> io::Result<Self> {
        private_directories(parent)?;
        loop {
            let path = parent.join(format!(".{prefix}-{}", Uuid::new_v4()));
            let mut builder = fs::DirBuilder::new();
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            match builder.create(&path) {
                Ok(()) => return Ok(Self { path }),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
    }

    pub(super) fn path(&self) -> &Path {
        &self.path
    }

    pub(super) fn close(self) -> io::Result<()> {
        fs::remove_dir_all(&self.path)
    }
}

impl Drop for PrivateDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// A separate create-new lease serializes publication to the canonical output.
/// A killed invocation leaves a private lock for explicit operator inspection;
/// a later invocation never automatically removes that unknown evidence.
pub(super) struct OutputLease {
    path: PathBuf,
    _file: File,
}
impl OutputLease {
    pub(super) fn acquire(dest: &Path) -> io::Result<Self> {
        let parent = dest.parent().ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "output requires a parent")
        })?;
        private_directories(parent)?;
        let name = dest
            .file_name()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "output requires a name"))?;
        let path = parent.join(format!(".{}.offline-migrate-lock", name.to_string_lossy()));
        Ok(Self {
            _file: private_file(&path)?,
            path,
        })
    }
}
impl Drop for OutputLease {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

pub(super) fn publish_directory(source: &Path, dest: &Path) -> io::Result<()> {
    // An existing empty output is permitted, but symlinks/files and nonempty
    // directories are not owned and must remain untouched.
    match fs::symlink_metadata(dest) {
        Ok(metadata) if metadata.file_type().is_dir() => {
            fs::remove_dir(dest)?;
        }
        Ok(_) => {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "output is not an empty directory",
            ))
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    #[cfg(target_os = "linux")]
    {
        use std::ffi::CString;
        use std::os::unix::ffi::OsStrExt;
        let source = CString::new(source.as_os_str().as_bytes())?;
        let dest = CString::new(dest.as_os_str().as_bytes())?;
        // Do not replace a path created by another process after the empty-dir
        // check. Both inputs are local filesystem paths owned by this process.
        let result = unsafe {
            libc::renameat2(
                libc::AT_FDCWD,
                source.as_ptr(),
                libc::AT_FDCWD,
                dest.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        };
        if result != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(target_os = "linux"))]
    {
        if dest.try_exists()? {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "output appeared during migration",
            ));
        }
        fs::rename(source, dest)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn competing_output_leases_and_aliases_never_adopt_each_other() {
        let root = tempfile::tempdir().unwrap();
        let dest = physical_path(&root.path().join("out")).unwrap();
        let first = OutputLease::acquire(&dest).unwrap();
        assert!(OutputLease::acquire(&dest).is_err());
        let other = OutputLease::acquire(&root.path().join("other")).unwrap();
        drop(other);
        assert!(first.path.exists());
        drop(first);
        assert!(OutputLease::acquire(&dest).is_ok());
    }
}
