use std::{env, fs, path::Path};

fn collect(dir: &Path, root: &Path, output: &mut String) {
    let mut entries: Vec<_> = fs::read_dir(dir)
        .expect("read web build")
        .map(|e| e.unwrap())
        .collect();
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let path = entry.path();
        let kind = entry.file_type().unwrap();
        assert!(!kind.is_symlink(), "web assets must not contain symlinks");
        if kind.is_dir() {
            collect(&path, root, output);
        } else if kind.is_file() {
            let url = format!("/{}", path.strip_prefix(root).unwrap().to_str().unwrap());
            output.push_str(&format!(
                "({url:?}, include_bytes!({:?})),\n",
                path.to_str().unwrap()
            ));
        }
    }
}

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../web-dist");
    println!("cargo:rerun-if-changed={}", root.display());
    assert!(
        root.join("index.html").is_file(),
        "Build the browser UI first using MCTier-Linux-Web/scripts/build-web-server.sh"
    );
    let mut assets = String::from("pub const ASSETS: &[(&str, &[u8])] = &[\n");
    collect(
        &root.canonicalize().unwrap(),
        &root.canonicalize().unwrap(),
        &mut assets,
    );
    assets.push_str("];\n");
    fs::write(
        Path::new(&env::var("OUT_DIR").unwrap()).join("assets.rs"),
        assets,
    )
    .unwrap();
}
