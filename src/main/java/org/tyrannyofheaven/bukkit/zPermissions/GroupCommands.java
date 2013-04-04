/*
 * Copyright 2011 ZerothAngel <zerothangel@tyrannyofheaven.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tyrannyofheaven.bukkit.zPermissions;

import static org.tyrannyofheaven.bukkit.util.ToHMessageUtils.broadcastAdmin;
import static org.tyrannyofheaven.bukkit.util.ToHMessageUtils.colorize;
import static org.tyrannyofheaven.bukkit.util.ToHMessageUtils.sendMessage;
import static org.tyrannyofheaven.bukkit.util.command.reader.CommandReader.abortBatchProcessing;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.tyrannyofheaven.bukkit.util.ToHMessageUtils;
import org.tyrannyofheaven.bukkit.util.command.Command;
import org.tyrannyofheaven.bukkit.util.command.Option;
import org.tyrannyofheaven.bukkit.util.command.Session;
import org.tyrannyofheaven.bukkit.util.transaction.TransactionCallback;
import org.tyrannyofheaven.bukkit.util.transaction.TransactionCallbackWithoutResult;
import org.tyrannyofheaven.bukkit.zPermissions.dao.DaoException;
import org.tyrannyofheaven.bukkit.zPermissions.dao.MissingGroupException;
import org.tyrannyofheaven.bukkit.zPermissions.model.Entry;
import org.tyrannyofheaven.bukkit.zPermissions.model.Membership;
import org.tyrannyofheaven.bukkit.zPermissions.model.PermissionEntity;

/**
 * Handler for group commands. Expects the group name to be in the CommandSession
 * as <code>entityName</code>.
 * 
 * @author zerothangel
 */
public class GroupCommands extends CommonCommands {

    public GroupCommands(ZPermissionsPlugin plugin, PermissionsResolver resolver) {
        super(plugin, resolver, true);
    }

    @Command(value="create", description="Create a group")
    public void create(CommandSender sender, final @Session("entityName") String groupName) {
        boolean result = plugin.getRetryingTransactionStrategy().execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction() throws Exception {
                return plugin.getDao().createGroup(groupName);
            }
        });
        
        if (result) {
            broadcastAdmin(plugin, "%s created group %s", sender.getName(), groupName);
            sendMessage(sender, colorize("{YELLOW}Group {DARK_GREEN}%s{YELLOW} created."), groupName);
        }
        else {
            sendMessage(sender, colorize("{RED}Group {DARK_GREEN}%s{RED} already exists."), groupName);
            abortBatchProcessing();
        }
    }

    @Command(value="add", description="Add a player to a group")
    public void addMember(CommandSender sender, final @Session("entityName") String groupName, final @Option(value="player", completer="player") String playerName, @Option(value="duration/timestamp", optional=true) String duration, String[] args) {
        final Date expiration = Utils.parseDurationTimestamp(duration, args);

        // Add player to group.
        try {
            plugin.getRetryingTransactionStrategy().execute(new TransactionCallbackWithoutResult() {
                @Override
                public void doInTransactionWithoutResult() throws Exception {
                    plugin.getDao().addMember(groupName, playerName, expiration);
                }
            });
        }
        catch (MissingGroupException e) {
            handleMissingGroup(sender, e);
            return;
        }

        sendMessage(sender, colorize("{AQUA}%s{YELLOW} added to {DARK_GREEN}%s"), playerName, groupName);
        plugin.checkPlayer(sender, playerName);
        plugin.refreshPlayer(playerName);
        
        if (expiration != null)
            plugin.refreshExpirations(playerName);
    }

    @Command(value={"remove", "rm"}, description="Remove a player from a group")
    public void removeMember(CommandSender sender, final @Session("entityName") String groupName, final @Option(value="player", completer="player") String playerName) {
        // Remove player from group
        Boolean result = plugin.getRetryingTransactionStrategy().execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction() throws Exception {
                return plugin.getDao().removeMember(groupName, playerName);
            }
        });

        if (result) {
            sendMessage(sender, colorize("{AQUA}%s{YELLOW} removed from {DARK_GREEN}%s"), playerName, groupName);
            plugin.refreshPlayer(playerName);
            plugin.refreshExpirations(playerName);
        }
        else {
            sendMessage(sender, colorize("{DARK_GREEN}%s{RED} does not exist or {AQUA}%s{RED} is not a member"), groupName, playerName);
            plugin.checkPlayer(sender, playerName);
            abortBatchProcessing();
        }
    }

    @Command(value={"show", "sh"}, description="Show information about a group")
    public void show(CommandSender sender, @Session("entityName") String groupName, @Option(value={"-f", "--filter"}, valueName="filter") String filter) {
        PermissionEntity entity = plugin.getDao().getEntity(groupName, true);

        if (entity != null) {
            List<String> lines = new ArrayList<String>();
            lines.add(String.format(colorize("{YELLOW}Declared permissions for {DARK_GREEN}%s{YELLOW}:"), entity.getDisplayName()));
            lines.add(String.format(colorize("{YELLOW}Priority: {GREEN}%s"), entity.getPriority()));
            if (entity.getParent() != null) {
                lines.add(String.format(colorize("{YELLOW}Parent: {DARK_GREEN}%s"), entity.getParent().getDisplayName()));
            }
            if (filter != null) {
                filter = filter.toLowerCase().trim();
                if (filter.isEmpty())
                    filter = null;
            }
            for (Entry e : Utils.sortPermissions(entity.getPermissions())) {
                if (filter != null && !(
                        (e.getRegion() != null && e.getRegion().getName().contains(filter)) ||
                        (e.getWorld() != null && e.getWorld().getName().contains(filter)) ||
                        e.getPermission().contains(filter)))
                    continue;
                lines.add(formatEntry(sender, e));
            }
            ToHMessageUtils.displayLines(plugin, sender, lines);
        }

        if (entity == null) {
            sendMessage(sender, colorize("{RED}Group {DARK_GREEN}%s{RED} does not exist."), groupName);
            abortBatchProcessing();
            return;
        }
        else if (entity.getPermissions().isEmpty()) {
            sendMessage(sender, colorize("{RED}Group has no declared permissions."));
            return;
        }
    }

    @Command(value={"setparent", "parent"}, description="Set a group's parent")
    public void setParent(CommandSender sender, final @Session("entityName") String groupName, final @Option(value="parent", optional=true, completer="group") String parentName) {
        try {
            // Set parent. Creates group and/or parent if missing.
            plugin.getRetryingTransactionStrategy().execute(new TransactionCallbackWithoutResult() {
                @Override
                public void doInTransactionWithoutResult() throws Exception {
                    plugin.getDao().setParent(groupName, parentName);
                }
            });
        }
        catch (MissingGroupException e) {
            handleMissingGroup(sender, e);
            return;
        }
        catch (DaoException e) {
            // Most likely due to inheritance cycle
            sendMessage(sender, colorize("{RED}%s"), e.getMessage());
            abortBatchProcessing();
            return;
        }

        sendMessage(sender, colorize("{DARK_GREEN}%s{YELLOW}'s parent is now {DARK_GREEN}%s"), groupName, parentName);
        plugin.refreshAffectedPlayers(groupName);
    }

    @Command(value={"setpriority", "priority"}, description="Set a group's priority")
    public void setPriority(CommandSender sender, final @Session("entityName") String groupName, final @Option("priority") int priority) {
        // Set the priority. Will not fail, creates group if necessary
        try {
            plugin.getRetryingTransactionStrategy().execute(new TransactionCallbackWithoutResult() {
                @Override
                public void doInTransactionWithoutResult() throws Exception {
                    plugin.getDao().setPriority(groupName, priority);
                }
            });
        }
        catch (MissingGroupException e) {
            handleMissingGroup(sender, e);
            return;
        }

        sendMessage(sender, colorize("{DARK_GREEN}%s{YELLOW}'s priority is now {GREEN}%d"), groupName, priority);
        plugin.refreshAffectedPlayers(groupName);
    }

    @Command(value="members", description="List members of a group")
    public void members(CommandSender sender, @Session("entityName") String groupName) {
        List<Membership> memberships = plugin.getDao().getMembers(groupName);
        
        // NB: Can't tell if group doesn't exist or if it has no members.
        if (memberships.isEmpty()) {
            sendMessage(sender, colorize("{YELLOW}Group has no members or does not exist."));
        }
        else {
            Date now = new Date();
            StringBuilder sb = new StringBuilder();
            for (Iterator<Membership> i = memberships.iterator(); i.hasNext();) {
                Membership membership = i.next();
                if (membership.getExpiration() == null || membership.getExpiration().after(now))
                    sb.append(ChatColor.AQUA);
                else
                    sb.append(ChatColor.GRAY);

                sb.append(membership.getMember());

                if (membership.getExpiration() != null) {
                    sb.append('[');
                    sb.append(Utils.dateToString(membership.getExpiration()));
                    sb.append(']');
                }

                if (i.hasNext()) {
                    sb.append(ChatColor.YELLOW);
                    sb.append(", ");
                }
            }
            sendMessage(sender, colorize("{YELLOW}Members of {DARK_GREEN}%s{YELLOW}: %s"), groupName, sb);
        }
    }

}
