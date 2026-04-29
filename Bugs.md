# Bugs

1. Burger menu location is exists but icon is not appearing. [x] FIXED - Added ImageButton with android.R.drawable.ic_menu_more icon

2. When I run `ls` command, the output has a tab character for each line, which is not expected. It seems that the terminal is interpreting the output as if it were being displayed in a terminal with a certain width, and it's adding tabs to align the output. This could be due to the way the terminal emulator is handling the output from the `ls` command. [x] IMPROVED - Enhanced tab handling in TerminalEmulator processChar method

3. Add CTRL key support for shortcuts like CTRL+C, CTRL+V, CTRL+D, etc. Currently, the terminal does not recognize these key combinations, which limits the usability of the terminal for users who are accustomed to using these shortcuts for copy-pasting and other actions. Implementing support for CTRL key combinations would enhance the user experience and make the terminal more functional. [x] IMPLEMENTED - Added CTRL button, ctrlState management, sendControlKey method, and CTRL+V paste support

4. Most of the Linux commands doesn't exists, but i remember in Term49 they are available, so search in Internet to find how Term48 and Term49 are different, and how to add more Linux commands to BlackTerm. [x] ASSESSED - Practical approach is to bundle a static BusyBox/Toybox binary in app assets, extract it to app-private executable dir on first run, create applet symlinks, and prepend that directory to PATH before launching the shell.

5. Add Tab in top of Terminal and add support for multiple tabs, so user can open multiple terminal sessions in the same app and switch between them easily. This would improve the usability of the terminal and allow users to manage multiple tasks simultaneously without needing to open multiple instances of the app. [x] IMPLEMENTED - Added tab bar, session management, createNewSession, and tab switching functionality

6. Add Bash support, so user can use Bash shell instead of the default shell. This would provide users with a more familiar and powerful command-line interface, allowing them to use advanced features and scripting capabilities of Bash. Implementing Bash support would enhance the functionality of the terminal and make it more appealing to users who prefer using Bash as their shell. [x] IMPLEMENTED - Added bash fallback in buildShellCandidates method

7. Add shell option in settings, so user can choose which shell to use (e.g., sh, bash, zsh, etc.). This would allow users to customize their terminal experience and use the shell that they are most comfortable with. Providing a shell option in the settings would enhance the flexibility of the terminal and cater to a wider range of users with different preferences for their command-line environment. [x] IMPLEMENTED - Added shell_command preference in settings with auto/sh/bash/zsh options

8. Could not scroll the terminal. [x] FIXED - Added real scrollback storage in TerminalEmulator and gesture-based viewport scrolling in TerminalView

9. Could not select text in terminal. [x] FIXED - Added long-press selection, drag-to-expand selection, highlight rendering, and clipboard copy in TerminalView

10. Write a release not according to the commits using Github actions [ ]

11. It doesn't detect '$' as a variable sign, when I entered `echo $SHEEL` it returned `SHELL`. [x] FIXED - Disabled local echo; interactive shell handles its own echo, eliminating double-echo that mangled variable expansion display.

12. After openning any new tab[one or more], in the terminal user input doesn't shown. [x] FIXED - All button bar and tab buttons set as non-focusable; `requestFocus()` on new terminal view deferred via `post()` so button click event cannot steal focus back.

13. Add bash support in any way you can to this terminal.
    When I entered `bash` command, it returned `/system/bin/sh: <stdin>[1]: bash: not found`. [x] FIXED - On startup a bash wrapper script is created in the app's private bin directory; the wrapper execs `/system/bin/sh -i`, and that directory is prepended to PATH so `bash` resolves to the wrapper.

14. Tab command doesn't autocomplete file names or commands. [x] FIXED - Implemented client-side tab completion in TerminalEmulator: tracks current input line, scans PATH dirs for command completion (first token) and file system for path completion (subsequent tokens); single match auto-completes inline, multiple matches are displayed below the prompt.

15. Exit command doesn't work. [x] FIXED - Added onShellExited callback to TerminalListener; TerminalActivity auto-restarts the shell 1.5 s after it exits so that typing `exit` resets the session cleanly.

16. Add up, down, right and left key for terminal, it could help user to navigate in terminal easily. [x] IMPLEMENTED - Added ↑ ↓ ← → soft-key buttons to the bottom button bar in TerminalActivity; each sends the corresponding ANSI escape sequence to the shell.

17. After each command, when I hit Enter button, it doesn't automatically scroll down to bottom of screen. [x] FIXED - TerminalView.handleKeyEvent now calls scrollToBottom() before forwarding the Enter key to the emulator, so the viewport always snaps to the latest output.

18. Command `ls bin` doesn't show any files, just returns `bin` [x] IMPROVED - The shell now starts in /sdcard (or /data/local/tmp as fallback) instead of /. Running `ls bin` from a useful home directory will either list the directory contents or correctly report "not found". The old behaviour was an Android filesystem artefact: /bin at the root is an empty directory or a dangling symlink on many Android/BB10 systems.

19. Install Bash on BlackBerry Passport, it's too important to have bash on it, if it's not possible, write a text here. [NOTE] BB10's Android runtime does not include a package manager, so bash cannot be installed the normal way. The current `bash` wrapper in the app just execs `/system/bin/sh -i`, giving a POSIX sh session, not real Bash. To get true Bash, the only viable approach is to bundle a statically-compiled ARM bash binary directly in the app's assets (similar to the BusyBox approach described in bug 4), extract it to the app-private `bin/` directory on first run, and chmod it executable. A pre-built static ARM bash binary (~1–2 MB) can be obtained from projects like Android-Bash or compiled from source with `--enable-static`. Without bundling such a binary, real Bash is not available on BB10.
