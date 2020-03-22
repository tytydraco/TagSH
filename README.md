# TagSH
Execute universal shell scripts with the tap of an NFC tag

# Why?
Originally I made this app for running benchmarking commands. It can be a hassle to open up a terminal emulator, navigate to your script directory, and then execute it. It's much easier to just tap my NFC tag and have it run everything for me. Plus, then I can install TagSH on my other phones and run the same benchmark.

# Features
- No internet permission (your data is stored locally)
- ZLIB compression of scripts
- Flash your scripts directly from the app
- AMOLED terminal theme
- App can be closed when you scan the tag
- Script is stored directly on the tag, so it's universal
- Can work without root (permissions are limited of course)
- QR code and barcode scanning

# Tips
1. Make sure to use LF (UNIX) style line endings. Using the Windows default line ending style will break the script.
2. `#!/system/bin/sh` is optional. It may take up space on your NFC tag, and it is safe to remove.
3. Use `[ $(id -u) -eq 0 ] || exec su -c sh "$0" "$@"` to elevate your script to root permissions.
4. Use `input keyevent KEYCODE_HOME` or `input keyevent 3` to make your script exit TagSH on completion (elevated permissions required).
5. Clean up your code. Remove any unnecessary comments or extraneous code to make it small enough to fit on an NFC tag.
6. If you have too large of a script, consider using `curl [http://path/to/script.sh] | sh` in conjunction with tip #3

# Camera Permission
This app requires the camera permission, soley for scanning QR codes and barcodes. No data is sent online at all.

# Contact
- Telegram: @tytydraco
- Email: tylernij@gmail.com
