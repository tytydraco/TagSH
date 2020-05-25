# TagSH
Execute universal shell scripts with NFC tags, QR codes, or barcodes.

# Features
- ZLIB compression of scripts
- Flash your scripts directly from the app
- Dark material design compliant theme
- Script is stored directly on the tag (universal execution)
- Can work without root (permissions are limited)
- QR code and barcode scanning
- HTML code will open in a web view if detected

# Tips
1. Make sure to use LF (UNIX) style line endings. Using the Windows default line ending style will break the script.
2. `#!/system/bin/sh` is optional. It takes up space on the NFC tag, and it is safe to remove.
3. Use `[ $(id -u) -eq 0 ] || exec su -c sh "$0" "$@"` to elevate your script to root permissions.
4. Use `input keyevent KEYCODE_HOME` or `input keyevent 3` to make your script exit TagSH on completion (elevated permissions required).
5. Clean up your code. Remove any unnecessary comments or extraneous code to make it small enough to fit on an NFC tag.
6. If you have too large of a script, consider using `curl [http://path/to/script.sh] | sh` in conjunction with tip #3.
7. Always include `<!DOCTYPE html>` if you wish for your script to open in a web view rather than a terminal.

# Privacy Policy
The application owner, Tyler Nijmeh, is required to request your consent for this Privacy Policy in order to utilize the Camera permission on this device. Your camera data is used exclusively for QR code and barcode scanning functionality within the app itself. Your camera information never leaves this device in any form. No third-party organizations access your camera data. You may decline this Privacy Policy if you wish, however, QR code and barcode scanning functionality will not operate unless you accept the Privacy Policy. You will be prompted to accept the Privacy Policy when you attempt to click the "scan" menu item if you have not accepted it previously. This privacy policy is effective as of March 22nd, 2020.

# Disclaimer
Please note that this application does not include any form of warranty coverage for your device. Be aware that the user takes full responsibility when they choose to execute scripts using this application. It may be wise to use the appliation's built-in "View Only" setting to view the contents of the script _before_ deciding to execute it. It is highly recommended to take **extreme** precaution when executing unknown or untrusted scripts. If you have any concerns, please contact the developer of this application.

# Contact
- Telegram: @tytydraco
- Email: tylernij@gmail.com
