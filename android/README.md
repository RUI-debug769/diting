## 装机备忘（2026-11-05）

小米 HyperOS（25080RABDG / V816）上 `adb install` 会报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，
即使「开发者选项 → Install via USB」已打开。logcat 里能看到拦截者是 `com.miui.securitycenter.remote`
（`NewGlobalInstallCheckTask: blockType = 9`）。

原因是安装会话没有安装器身份。指定一个安装器包名即可通过：

    adb install -r -i com.android.vending app/build/diting.apk

`-i com.mi.android.globalFileexplorer` 同样有效。

另注：`adb exec-out screencap -p > x.png` 不要用 PowerShell 的 `>` 重定向，会写成 UTF-16；
用 `cmd.exe /c "... > x.png"` 才是二进制安全的。
