// Compile these exact upstream modules without a Tauri runtime or WebKitGTK.
#[path = "../../../src-tauri/src/modules/app_paths.rs"]
pub mod app_paths;
#[path = "../../../src-tauri/src/modules/chat_auth.rs"]
pub mod chat_auth;
#[path = "../shared/chat_service.rs"]
pub mod chat_service;
#[path = "../shared/chat_transport.rs"]
pub mod chat_transport;
#[path = "../shared/config_types.rs"]
pub mod config_manager;
#[path = "../../../src-tauri/src/modules/error.rs"]
pub mod error;
#[path = "../../../src-tauri/src/modules/http_cors.rs"]
pub mod http_cors;
#[path = "../../../src-tauri/src/modules/lobby_address.rs"]
pub mod lobby_address;
#[path = "../shared/network_arguments.rs"]
pub mod network_arguments;

#[path = "../../../src-tauri/src/modules/virtual_network.rs"]
pub mod virtual_network;

#[path = "../../../src-tauri/src/modules/file_transfer.rs"]
pub mod file_transfer;
#[path = "../../../src-tauri/src/modules/hosts_manager.rs"]
pub mod hosts_manager;
#[path = "../../../src-tauri/src/modules/hosts_security.rs"]
pub mod hosts_security;
#[path = "../shared/minecraft_discovery.rs"]
pub mod minecraft_discovery;
#[path = "../shared/secret_store.rs"]
pub mod secret_store;
#[path = "../../../src-tauri/src/modules/unix_hosts_helper.rs"]
pub mod unix_hosts_helper;
