package org.duckdns.dunevoice.core;

/** A command contains data, never a shell command, URL or Android component. */
public final class Command {
    public final String action;
    public final String argument;
    public Command(String action, String argument) {
        this.action = action;
        this.argument = argument;
    }
}
