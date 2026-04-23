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