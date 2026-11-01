use narada_protocol::RpcError;
use std::path::{Component, Path};
use unicode_normalization::UnicodeNormalization;
pub fn err(code: &str, message: &str) -> RpcError {
    RpcError::new(code, message)
}
pub fn fold(s: &str) -> String {
    s.nfc().flat_map(char::to_uppercase).collect()
}
pub fn component(s: &str) -> Result<(), RpcError> {
    let stem = s.split('.').next().unwrap_or("").to_uppercase();
    let reserved = matches!(
        stem.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
    ) || ["COM", "LPT"].iter().any(|p| {
        stem.strip_prefix(p).is_some_and(|n| {
            matches!(
                n,
                "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
            )
        })
    });
    if s.is_empty()||s.len()>180||matches!(s,"."|"..")||s.ends_with(['.',' '])||s.chars().any(|c|c.is_control()||matches!(c,'/'|'\\'|':'|'<'|'>'|'"'|'|'|'?'|'*'|'\u{202a}'..='\u{202e}'|'\u{2066}'..='\u{2069}'))||s.nfc().collect::<String>()!=s||reserved{
  Err(err("UNSAFE_NAME","Use an NFC name of at most 180 UTF-8 bytes without traversal, separators, reserved device names, controls, bidi overrides, or trailing dots/spaces."))
 }else{Ok(())}
}
pub fn relative(s: &str) -> Result<(), RpcError> {
    if s.len() > 2048 || s.starts_with('/') || s.contains('\\') || s.split('/').count() > 32 {
        return Err(err(
            "UNSAFE_PATH",
            "The relative location exceeds the supported boundary.",
        ));
    }
    if !s.is_empty() {
        for part in s.split('/') {
            component(part)?;
        }
    }
    Ok(())
}
pub fn exclusions(values: &[String]) -> Result<(), RpcError> {
    if values.len() > 16 || values.iter().map(String::len).sum::<usize>() > 1024 {
        return Err(err(
            "INVALID_EXCLUSIONS",
            "Use at most 16 prefixes totaling at most 1,024 UTF-8 bytes.",
        ));
    }
    let mut seen = std::collections::HashSet::new();
    for v in values {
        relative(v)?;
        if v.is_empty() || v.len() > 512 || !seen.insert(fold(v)) {
            return Err(err("INVALID_EXCLUSIONS","Exclusions must be unique, nonempty relative folder/file prefixes, not wildcards or host paths."));
        }
    }
    Ok(())
}
pub fn excluded(rel: &str, prefixes: &[String]) -> bool {
    if relative(rel).is_err() {
        return true;
    }
    if rel.split('/').any(|c| {
        c.starts_with('.')
            || matches!(
                fold(c).as_str(),
                "DESKTOP.INI" | "THUMBS.DB" | "$RECYCLE.BIN" | "SYSTEM VOLUME INFORMATION"
            )
    }) {
        return true;
    }
    let candidate = fold(rel);
    prefixes.iter().any(|p| {
        let p = fold(p);
        candidate == p || candidate.starts_with(&(p + "/"))
    })
}
pub fn root_path(path: &Path, data_dir: &Path) -> Result<(), RpcError> {
    if !path.is_absolute()
        || path.as_os_str().len() > 4096
        || path
            .components()
            .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
        || path.parent().is_none()
    {
        return Err(err(
            "UNSAFE_ROOT",
            "Choose an explicit local folder, not a disk root, relative path or namespace.",
        ));
    }
    let text = path.to_string_lossy();
    if text.starts_with("\\\\") || text.starts_with("//") || text.contains('\0') {
        return Err(err(
            "UNSAFE_ROOT",
            "UNC, device namespaces and remote shares are not supported.",
        ));
    }
    if within(path, data_dir) || within(data_dir, path) {
        return Err(err(
            "PROTECTED_ROOT",
            "The agent profile and its ancestors cannot be registered as shared folders.",
        ));
    }
    if path
        .components()
        .filter_map(|c| {
            if let Component::Normal(s) = c {
                s.to_str()
            } else {
                None
            }
        })
        .any(|c| {
            matches!(
                fold(c).as_str(),
                ".SSH"
                    | ".GNUPG"
                    | ".AWS"
                    | ".AZURE"
                    | ".CACHE"
                    | ".GIT"
                    | ".NARADA-DATA"
                    | "APPDATA"
                    | "WINDOWS"
                    | "PROGRAM FILES"
                    | "PROGRAM FILES (X86)"
                    | "PROGRAMDATA"
                    | "SYSTEM VOLUME INFORMATION"
                    | "$RECYCLE.BIN"
            )
        })
    {
        return Err(err(
            "PROTECTED_ROOT",
            "System, application-state and credential directories cannot be registered.",
        ));
    }
    #[cfg(unix)]
    if [
        "/", "/home", "/root", "/tmp", "/var", "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64",
        "/opt", "/proc", "/dev", "/sys", "/run",
    ]
    .iter()
    .any(|p| path == Path::new(p))
        || [
            "/etc", "/proc", "/dev", "/sys", "/run", "/usr", "/bin", "/lib", "/sbin",
        ]
        .iter()
        .any(|p| path.starts_with(p))
    {
        return Err(err(
            "PROTECTED_ROOT",
            "Choose a specific user-data folder, not a system directory.",
        ));
    }
    Ok(())
}

pub fn within(path: &Path, parent: &Path) -> bool {
    let a: Vec<_> = path
        .components()
        .map(|s| fold(&s.as_os_str().to_string_lossy()))
        .collect();
    let b: Vec<_> = parent
        .components()
        .map(|s| fold(&s.as_os_str().to_string_lossy()))
        .collect();
    a.starts_with(&b)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hostile_names_fail() {
        for v in [
            "",
            ".",
            "..",
            "../a",
            "a/b",
            "a\\b",
            "file:stream",
            "CON",
            "nul.txt",
            "COM1",
            "LPT².txt",
            "CONIN$",
            "a.",
            "a ",
            "a\n",
            "a\u{202e}txt",
            "e\u{301}",
        ] {
            assert!(component(v).is_err(), "{v:?}");
        }
        assert!(component("నారద.png").is_ok());
    }
    #[test]
    fn prefix_exclusions_use_component_boundaries() {
        let e = vec!["Secret".into()];
        assert!(excluded("secret/notes", &e));
        assert!(!excluded("secretary/notes", &e));
        assert!(excluded("a/.env", &[]));
        assert!(excluded(".narada-trash/a", &[]));
    }
    #[test]
    fn case_and_unicode_collisions_are_conservative() {
        assert_eq!(fold("straße"), fold("STRASSE"));
        assert_eq!(fold("Résumé"), fold("RÉSUMÉ"));
        assert!(exclusions(&["A".into(), "a".into()]).is_err());
    }
    #[test]
    fn paths_cannot_approve_all_data() {
        let d = Path::new("/home/user/app/data");
        for p in [
            "/",
            "/etc",
            "/home/user",
            "/home/user/app/data",
            "/home/user/.ssh/keys",
        ] {
            assert!(root_path(Path::new(p), d).is_err());
        }
    }
}

#[cfg(test)]
mod corpus_tests {
    #[test]
    fn shared_windows_and_path_corpus_fails_closed() {
        let values: Vec<String> = serde_json::from_str(include_str!(
            "../../../tests/corpus/paths/windows-hostile.json"
        ))
        .unwrap();
        for path in values {
            assert!(
                super::relative(&path).is_err(),
                "An unsafe corpus path was admitted"
            );
        }
    }
}
