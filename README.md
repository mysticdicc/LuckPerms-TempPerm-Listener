# LuckPerms TempPerms Listener
This is a Minecraft Paper Server plugin for 1.26.2. It listens for AddNode events from the LuckPerms plugins, specifically for temporary assignments of permissions.
Depending on the permission that was assigned, different commands are scheduled to revoke their functionality when the permission expires, allowing for the temporary
assignment of permissions without functionality leaking.

## Current Permission Mapping
| Permission | Command Mapping |
| -------- | ------- |
| essentials.fly | fly <username> disabled |
| essentials.god | god <username> disabled |

