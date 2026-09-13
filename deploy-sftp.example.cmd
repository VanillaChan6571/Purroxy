@echo off
rem Copy this file to deploy-sftp.local.cmd and fill it in. The local copy is gitignored so
rem credentials and host details never reach the repository.
rem
rem Any setting can be given per server as PURROXY_<N>_<NAME> or shared as PURROXY_<NAME>. The per-server
rem form wins. Hosts, ports and users usually differ; passwords and remote directories usually do not.

set "SERVERS=1"
set "PURROXY_SFTP_ENABLED=1"

set "PURROXY_1_SFTP_HOST=node01.example.com"
set "PURROXY_1_SFTP_PORT=2022"
set "PURROXY_1_SFTP_USER=vanillachanny.nodeid"

rem Shared across every server above.
set "PURROXY_SFTP_REMOTE_DIR=/"

rem Pterodactyl-style password auth. Requires WinSCP, because OpenSSH sftp cannot take a password
rem non-interactively. Leave the password unset to use the key/agent path below instead.
set "PURROXY_SFTP_PASSWORD=your-panel-sftp-password"
set "PURROXY_WINSCP=C:\Program Files (x86)\WinSCP\WinSCP.com"

rem Key or agent auth, used whenever no password is set. Needs no WinSCP install.
rem set "PURROXY_SFTP_KEY=C:\Users\VanillaChanny\.ssh\id_ed25519"

rem Upload under a fixed name instead of the built jar's name. Set this if the panel's startup
rem command names a specific file - otherwise each build lands beside the last one and the server
rem keeps booting the old jar.
rem set "PURROXY_SFTP_REMOTE_NAME=Purroxy.jar"

rem Recommended: pin the host key from the first trusted connection, so a swapped server is caught.
rem set "PURROXY_SFTP_HOSTKEY=ssh-ed25519 255 SHA256:example"

rem Less secure fallback when the node rebuilds and rotates its key. Trusts whatever answers.
set "PURROXY_SFTP_ACCEPT_ANY_HOSTKEY=1"
