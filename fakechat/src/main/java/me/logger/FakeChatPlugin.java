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
        getLogger().info("FakeChat включён!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Проверяем права (по умолчанию только у OP)
        if (!sender.hasPermission("fakechat.use")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды.");
            return true;
        }

        // Проверяем, ввели ли ник и сообщение
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Использование: /" + label + " <игрок> <сообщение>");
            return true;
        }

        // Ищем игрока по нику
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Игрок с ником " + args[0] + " не найден в сети.");
            return true;
        }

        // Собираем сообщение из всех слов после ника
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Отправляем сообщение от лица игрока.
        // Внимание: метод chat() помечен как Deprecated (устаревший), но на 1.20.1 он отлично работает
        // и заставляет сообщение пройти через все плагины чата (префиксы, кланы и т.д.).
        target.chat(message);

        sender.sendMessage(ChatColor.GREEN + "Сообщение отправлено от имени " + target.getName());
        return true;
    }
}