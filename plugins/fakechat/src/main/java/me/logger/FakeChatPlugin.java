package me.logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class FakeChatPlugin extends JavaPlugin implements CommandExecutor {

    @Override
    public void onEnable() {
        // Регистрируем команду
        if (this.getCommand("fakechat") != null) {
            this.getCommand("fakechat").setExecutor(this);
        }
        getLogger().info("FakeChat enabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Проверяем права (по умолчанию только у OP)
        if (!sender.hasPermission("fakechat.use")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        // Проверяем, ввели ли ник и сообщение
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player> <message>");
            return true;
        }

        // Ищем игрока по нику
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player with name " + args[0] + " is not online.");
            return true;
        }

        // Собираем сообщение из всех слов после ника
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Отправляем сообщение от лица игрока.
        // Внимание: метод chat() помечен как Deprecated (устаревший), но на 1.20.1 он отлично работает
        // и заставляет сообщение пройти через все плагины чата (префиксы, кланы и т.д.).
        target.chat(message);

        sender.sendMessage(ChatColor.GREEN + "Message sent on behalf of " + target.getName());
        return true;
    }
}