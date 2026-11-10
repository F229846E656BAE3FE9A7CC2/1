package ru.example.commandlogger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class CommandLoggerPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private File logFile;
    private final Object writeLock = new Object();

    @Override
    public void onEnable() {
        logFile = new File(Bukkit.getWorldContainer(), "commands.log");

        try {
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
        } catch (IOException e) {
            getLogger().severe("Не удалось создать файл commands.log: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CommandLogger включён. Лог команд: " + logFile.getAbsolutePath());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String rawCommand = event.getMessage();

        if (rawCommand == null || rawCommand.isEmpty()) {
            return;
        }

        String time = LocalDateTime.now().format(TIME_FORMAT);
        String logLine = String.format("[%s] %s (%s): %s%n",
                time,
                player.getName(),
                player.getUniqueId(),
                rawCommand
        );

        writeToFile(logLine);
    }

    private void writeToFile(String line) {
        synchronized (writeLock) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(line);
            } catch (IOException e) {
                getLogger().warning("Не удалось записать команду в лог: " + e.getMessage());
            }
        }
    }
}