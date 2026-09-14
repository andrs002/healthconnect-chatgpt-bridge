# 手機完成編譯 APK：最簡單流程

這個版本已加入 GitHub Actions。你不需要 Android Studio，也不需要電腦。

## 你需要
- Android 手機
- GitHub 帳號
- 手機瀏覽器（Chrome 即可）

## 步驟 1：建立 GitHub Repository
1. 打開 github.com
2. 登入
3. 點右上角 `+`
4. 選 `New repository`
5. Repository name 填：
   `healthconnect-chatgpt-bridge`
6. 選 Private
7. 點 `Create repository`

## 步驟 2：上傳這個專案
最簡單方法：
1. 在手機把這個 ZIP 解壓縮
2. GitHub Repository 內點：
   `Add file` → `Upload files`
3. 上傳解壓縮後的所有檔案與資料夾
4. Commit changes

注意：`.github` 是隱藏資料夾；如果手機檔案管理器不方便選取，建議先在 ZIP 解壓後確認它存在。

## 步驟 3：執行雲端編譯
Repository 頁面：
1. 點 `Actions`
2. 找 `Build Android APK`
3. 點 `Run workflow`
4. 再按一次綠色 `Run workflow`
5. 等待數分鐘

如果上傳後有 push，通常也會自動開始。

## 步驟 4：下載 APK
編譯成功後：
1. 打開該次 workflow
2. 往下看到 `Artifacts`
3. 點 `HCBridge-debug-apk`
4. 下載 ZIP
5. 解壓縮
6. 裡面就是 APK

APK 名稱通常類似：
`app-debug.apk`

## 步驟 5：安裝
Android 若阻擋：
設定 → 安全性/隱私 → 安裝未知應用程式
允許你目前使用的瀏覽器或「我的檔案」安裝。

然後點 APK 安裝。

## 重要
目前這個 APK 仍是工程測試版。

它會讀 Health Connect 權限，但後端網址與登入 Token 還沒有正式設定，因此「安裝成功」不等於「已經可以把資料送到 ChatGPT」。

下一階段要完成：
1. 部署 backend
2. Android App 改成真正登入
3. 設定 backend URL
4. 做 ChatGPT/MCP OAuth
5. 手機 App 與 ChatGPT 使用同一帳號
