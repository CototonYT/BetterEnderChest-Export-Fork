package nl.rutgerkok.betterenderchest.command;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import nl.rutgerkok.betterenderchest.BetterEnderChest;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class EnderChestToPlayerDataCommand implements CommandExecutor {

    private final BetterEnderChest plugin;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public EnderChestToPlayerDataCommand(BetterEnderChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("betterenderchest.command.export")) {
            sender.sendMessage(ChatColor.RED + "Нет прав на команду.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Переношу предметы из chestData в playerdata всех игроков...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin.getPlugin(), () -> {
            File chestFolder = plugin.getChestSaveLocation();
            if (chestFolder == null || !chestFolder.exists()) {
                sender.sendMessage(ChatColor.RED + "Папка chestData не найдена: " + chestFolder);
                return;
            }

            AtomicInteger success = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();

            scanFolder(chestFolder, success, failed, skipped);

            Bukkit.getScheduler().runTask(plugin.getPlugin(), () -> {
                sender.sendMessage(ChatColor.GREEN + "Готово!");
                sender.sendMessage(ChatColor.GREEN + "  Успешно: " + success.get());
                sender.sendMessage(ChatColor.RED + "  Ошибок: " + failed.get());
                sender.sendMessage(ChatColor.YELLOW + "  Пропущено (нет playerdata): " + skipped.get());
            });
        });

        return true;
    }

    private void scanFolder(File folder, AtomicInteger success, AtomicInteger failed, AtomicInteger skipped) {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // Подпапки — это world groups. Обрабатываем все.
                scanFolder(file, success, failed, skipped);
            } else if (file.getName().endsWith(".dat")) {
                processChestFile(file, success, failed, skipped);
            }
        }
    }

    private void processChestFile(File chestFile, AtomicInteger success, AtomicInteger failed, AtomicInteger skipped) {
        String fileName = chestFile.getName().substring(0, chestFile.getName().length() - 4);

        if (!UUID_PATTERN.matcher(fileName).matches()) {
            plugin.warning("Пропуск (не UUID): " + chestFile.getName());
            return;
        }

        UUID uuid = UUID.fromString(fileName);

        try {
            File playerFile = findPlayerDataFile(uuid);
            if (playerFile == null || !playerFile.exists()) {
                plugin.warning("Нет playerdata для " + uuid + " (игрок не заходил?), пропуск");
                skipped.incrementAndGet();
                return;
            }

            // 1. Читаем NBT-файл сундука BetterEnderChest
            CompoundTag chestNbt = NbtIo.readCompressed(chestFile.toPath(), NbtAccounter.unlimitedHeap());
            ListTag chestItems = chestNbt.getList("Inventory").orElse(new ListTag());

            // 2. Читаем NBT-файл игрока
            CompoundTag playerNbt = NbtIo.readCompressed(playerFile.toPath(), NbtAccounter.unlimitedHeap());

            // 3. Собираем новый EnderItems
            ListTag enderItems = new ListTag();
            int written = 0;
            int dropped = 0;

            for (int i = 0; i < chestItems.size(); i++) {
                CompoundTag itemTag = chestItems.getCompound(i).orElse(null);
                if (itemTag == null) continue;

                CompoundTag copy = itemTag.copy();
                int slot = copy.getByte("Slot").orElse((byte) 0);

                // Ванильный эндер-сундук имеет только слоты 0..26
                if (slot < 0 || slot > 26) {
                    dropped++;
                    continue;
                }

                enderItems.add(copy);
                written++;
            }

            // 4. Заменяем EnderItems в playerdata
            playerNbt.put("EnderItems", enderItems);

            // 5. Сохраняем обратно
            NbtIo.writeCompressed(playerNbt, playerFile.toPath());

            String msg = "Перенесено " + uuid + " → " + written + " предметов";
            if (dropped > 0) {
                msg += " (" + dropped + " отброшено: слот > 26)";
                plugin.warning("У " + uuid + " часть предметов не поместилась в ванильный эндер-сундук");
            }
            plugin.log(msg);
            success.incrementAndGet();

        } catch (Exception e) {
            plugin.severe("Ошибка при обработке " + uuid + ": " + e.getMessage());
            e.printStackTrace();
            failed.incrementAndGet();
        }
    }

    /**
     * Ищет playerdata-файл игрока. Поддерживает новый путь Paper 26.3+ (players/data/...)
     * и старый путь (playerdata/...).
     */
    private File findPlayerDataFile(UUID uuid) {
        World mainWorld = Bukkit.getWorlds().get(0);
        File worldRoot = new File(Bukkit.getWorldContainer(), mainWorld.getName());

        // Paper 26.3+: world/players/data/<uuid>.dat
        File newPath = new File(worldRoot, "players/data/" + uuid + ".dat");
        if (newPath.exists()) return newPath;

        // Legacy: world/playerdata/<uuid>.dat
        File legacyPath = new File(worldRoot, "playerdata/" + uuid + ".dat");
        if (legacyPath.exists()) return legacyPath;

        return null;
    }
}