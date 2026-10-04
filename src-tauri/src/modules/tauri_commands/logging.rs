use super::shared::*;

/// 打开日志文件所在的文件夹
///
/// # 返回
/// * `Ok(())` - 成功
/// * `Err(String)` - 错误信息
#[tauri::command]
pub async fn open_log_folder() -> Result<(), String> {
    log::info!("打开日志文件夹");

    // 获取日志文件路径
    let log_path = crate::modules::app_paths::data_root().map_err(|e| e.to_string())?;

    log::info!("日志文件夹路径: {:?}", log_path);

    // 确保目录存在
    if !log_path.exists() {
        return Err("日志文件夹不存在".to_string());
    }

    // 打开文件夹
    #[cfg(target_os = "windows")]
    {
        use std::process::Command;
        match Command::new(windows_system_command("explorer.exe"))
            .arg(&log_path)
            .spawn()
        {
            Ok(_) => {
                log::info!("✅ 成功打开日志文件夹");
                Ok(())
            }
            Err(e) => {
                log::error!("❌ 打开日志文件夹失败: {}", e);
                Err(format!("打开日志文件夹失败: {}", e))
            }
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        Err("当前平台不支持此功能".to_string())
    }
}

/// 打开日志文件（使用默认文本编辑器）
///
/// # 返回
/// * `Ok(())` - 成功
/// * `Err(String)` - 错误信息
#[tauri::command]
pub async fn open_log_file() -> Result<(), String> {
    log::info!("打开日志文件");

    // 获取日志文件路径
    let log_path = crate::modules::app_paths::log_path().map_err(|e| e.to_string())?;

    log::info!("日志文件路径: {:?}", log_path);

    // 确保文件存在
    if !log_path.exists() {
        return Err("日志文件不存在".to_string());
    }

    // 打开文件
    #[cfg(target_os = "windows")]
    {
        use std::process::Command;
        // 使用notepad打开日志文件
        match Command::new(windows_system_command("notepad.exe"))
            .arg(&log_path)
            .spawn()
        {
            Ok(_) => {
                log::info!("✅ 成功打开日志文件");
                Ok(())
            }
            Err(e) => {
                log::error!("❌ 打开日志文件失败: {}", e);
                Err(format!("打开日志文件失败: {}", e))
            }
        }
    }

    #[cfg(not(target_os = "windows"))]
    {
        Err("当前平台不支持此功能".to_string())
    }
}

/// 获取日志文件路径
///
/// # 返回
/// * `Ok(String)` - 日志文件路径
/// * `Err(String)` - 错误信息
#[tauri::command]
pub async fn get_log_file_path() -> Result<String, String> {
    let log_path = crate::modules::app_paths::log_path().map_err(|e| e.to_string())?;

    Ok(log_path.to_string_lossy().to_string())
}

/// 读取最近的运行日志，供设置页内查看。仅返回末尾内容，避免日志过大阻塞界面。
#[tauri::command]
pub async fn read_log_file() -> Result<String, String> {
    let log_path = crate::modules::app_paths::log_path().map_err(|e| e.to_string())?;

    let bytes = tokio::fs::read(&log_path)
        .await
        .map_err(|e| format!("读取日志失败: {}", e))?;
    const MAX_BYTES: usize = 512 * 1024;
    let start = bytes.len().saturating_sub(MAX_BYTES);
    let mut content = String::from_utf8_lossy(&bytes[start..]).into_owned();
    if start > 0 {
        content = format!("[仅显示最近 512 KB 日志]\n{}", content);
    }
    Ok(content)
}
