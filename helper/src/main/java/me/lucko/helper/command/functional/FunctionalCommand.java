/*
 * This file is part of helper, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.helper.command.functional;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.google.common.collect.ImmutableList;

import me.lucko.helper.command.AbstractCommand;
import me.lucko.helper.command.CommandInterruptException;
import me.lucko.helper.command.context.CommandContext;
import me.lucko.helper.command.context.ImmutableCommandContext;
import me.lucko.helper.utils.annotation.NonnullByDefault;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@NonnullByDefault
class FunctionalCommand extends AbstractCommand {
    private final ImmutableList<Predicate<CommandContext<?>>> predicates;
    private final FunctionalCommandHandler handler;
    private final @Nullable FunctionalTabHandler tabHandler;
    private final @Nullable FunctionalAsyncTabHandler asyncTabHandler;

    FunctionalCommand(ImmutableList<Predicate<CommandContext<?>>> predicates, FunctionalCommandHandler handler, @Nullable FunctionalTabHandler tabHandler, @Nullable FunctionalAsyncTabHandler asyncTabHandler, @Nullable String permission, @Nullable String permissionMessage, @Nullable String description) {
        this.predicates = predicates;
        this.handler = handler;
        this.tabHandler = tabHandler;
        this.asyncTabHandler = asyncTabHandler;
        this.permission = permission;
        this.permissionMessage = permissionMessage;
        this.description = description;
    }

    @Override
    public void call(@Nonnull CommandContext<?> context) throws CommandInterruptException {
        for (Predicate<CommandContext<?>> predicate : this.predicates) {
            if (!predicate.test(context)) {
                return;
            }
        }

        //noinspection unchecked
        this.handler.handle(context);
    }

    @Nullable
    @Override
    public List<String> callTabCompleter(@Nonnull CommandContext<?> context) throws CommandInterruptException {
        if (tabHandler == null) {
            return null;
        }
        for (Predicate<CommandContext<?>> predicate : this.predicates) {
            if (!predicate.test(context)) {
                return null;
            }
        }

        //noinspection unchecked
        return this.tabHandler.handle(context);
    }

    @EventHandler
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (asyncTabHandler == null || !event.isCommand() || event.isHandled()) {
            return;
        }

        // Extract command information from the buffer
        String buffer = event.getBuffer();
        String[] parts = buffer.split(" ");
        
        // Get the command label from buffer (remove leading slash if present)
        String bufferLabel = parts.length > 0 ? parts[0].startsWith("/") ? parts[0].substring(1) : parts[0] : "";

        // Remove the command name and construct arguments
        String[] args = new String[parts.length - 1];
        if (parts.length > 1) {
            System.arraycopy(parts, 1, args, 0, parts.length - 1);
        }

        // Create command context from the event
        CommandContext<CommandSender> context = new ImmutableCommandContext<>(
            event.getSender(),
            bufferLabel,
            args,
            ImmutableList.of()
        );

        // Check predicates first, just like in callTabCompleter
        for (Predicate<CommandContext<?>> predicate : this.predicates) {
            if (!predicate.test(context)) {
                return;
            }
        }

        try {
            //noinspection unchecked
            List<String> completions = this.asyncTabHandler.handle((CommandContext<CommandSender>) context);
            if (completions != null) {
                event.setCompletions(completions);
            }
        } catch (CommandInterruptException e) {
            // Handle the exception if needed, but for async tab complete, we might not need to do anything special
        }
    }
}
