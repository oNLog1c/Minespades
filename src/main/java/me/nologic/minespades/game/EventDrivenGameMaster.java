package me.nologic.minespades.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import me.nologic.minespades.Minespades;
import me.nologic.minespades.battleground.Battleground;
import me.nologic.minespades.battleground.BattlegroundPlayer;
import me.nologic.minespades.game.event.BattlegroundPlayerDeathEvent;
import me.nologic.minespades.game.event.PlayerEnterBattlegroundEvent;
import me.nologic.minespades.game.event.PlayerQuitBattlegroundEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class EventDrivenGameMaster implements Listener {

    @Getter
    private final PlayerManager playerManager = new PlayerManager();

    @EventHandler
    private void whenPlayerEnterBattleground(PlayerEnterBattlegroundEvent event) {
        Battleground battleground = event.getBattleground();
        if (battleground.isValid() && playerManager.getBattlegroundPlayer(event.getPlayer()) == null) {
            playerManager.save(event.getPlayer());
            playerManager.getPlayersInGame().add(battleground.connect(event.getPlayer()));
            Component name = event.getPlayer().name().color(TextColor.fromHexString("#" + event.getTeam().getColor()));
            event.getPlayer().playerListName(name);
            event.getPlayer().displayName(name);
            event.getPlayer().setHealth(20);
            event.getPlayer().setFoodLevel(20);
        } else {
            event.getPlayer().sendMessage("§4Подключение неудачно. Арена отключена или вы уже в игре.");
        }
    }

    @EventHandler
    private void whenPlayerQuitBattleground(PlayerQuitBattlegroundEvent event) {
        BattlegroundPlayer battlegroundPlayer = playerManager.getBattlegroundPlayer(event.getPlayer());
        if (battlegroundPlayer != null) {
            battlegroundPlayer.getBattleground().kick(battlegroundPlayer);
            playerManager.getPlayersInGame().remove(battlegroundPlayer);
            playerManager.load(event.getPlayer());
            event.getPlayer().displayName(event.getPlayer().name().color(NamedTextColor.WHITE));
            event.getPlayer().playerListName(event.getPlayer().name().color(NamedTextColor.WHITE));

            // Проверяем игроков на спектаторов. Если в команде начали появляться спектаторы, то
            // значит у неё закончились жизни. Если последний живой игрок ливнёт, а мы не обработаем
            // событие выхода, то игра встанет. Поэтому нужно всегда проверять команду.
            if (event.getTeam().getLifepool() == 0 && event.getTeam().getPlayers().size() > 1) {
                boolean everyPlayerInTeamIsSpectator = true;
                for (Player p : event.getTeam().getPlayers()) {
                    if (p.getGameMode() == GameMode.SURVIVAL) {
                        everyPlayerInTeamIsSpectator = false;
                        break;
                    }
                }
                if (everyPlayerInTeamIsSpectator) {
                    // TODO: team lose event
                    event.getBattleground().broadcast(Component.text("Команда " + event.getTeam().getName() + " проиграла.").color(TextColor.color(226, 66, 43)).decorate(TextDecoration.BOLD));
                    Minespades.getPlugin(Minespades.class).getBattlegrounder().reset(event.getBattleground());
                }
            }
        }
    }

    @EventHandler
    private void onBattlegroundPlayerDeath(BattlegroundPlayerDeathEvent event) {

        TextComponent textComponent;
        Player player = event.getVictim().getPlayer();

        // TODO: создать класс Killfeed, который бы отправлял игрокам сообщения об игровых событиях
        if (event.getKiller() != null) {
            textComponent = Component.text(" > ")
                    .color(TextColor.color(0xCED4C8))
                    .append(player.name().color(TextColor.fromHexString("#" + event.getVictim().getTeam().getColor())))
                    .append(Component.text(" был убит "))
                    .append(event.getKiller().getPlayer().name().color(TextColor.fromHexString("#" + event.getKiller().getTeam().getColor())))
                    .append(Component.text("!"));
        } else {
            textComponent = Component.text(" > ")
                    .color(TextColor.color(0xCACAD9))
                    .append(player.name().color(TextColor.fromHexString("#" + event.getVictim().getTeam().getColor())))
                    .append(Component.text(" умер.."));
        }

        // Довольно простая механика лайфпулов. После смерти игрока лайфпул команды уменьшается.
        // Если игрок умер, а очков жизней больше нет — игрок становится спектатором.
        // Если в команде умершего игрока все игроки в спеке, то значит команда проиграла.
        // TODO: Необходимо заблочить телепортацию в режиме наблюдателя.
        int lifepool = event.getVictim().getTeam().getLifepool();
        if (lifepool >= 1) {
            event.getVictim().getTeam().setLifepool(lifepool - 1);
            event.getVictim().setRandomLoadout();
            switch (event.getRespawnMethod()) {
                case QUICK -> player.teleport(event.getVictim().getTeam().getRandomRespawnLocation());
                case AOS -> player.sendMessage("не реализовано...");
                case NORMAL -> player.sendMessage("lol ok");
            }

            player.setNoDamageTicks(40);
            player.setHealth(20);
            player.setFoodLevel(20);
            Bukkit.getScheduler().runTaskLater(playerManager.plugin, () -> {
                event.getBattleground().broadcast(textComponent);
                player.setFireTicks(0);
                player.getActivePotionEffects().forEach(potionEffect -> player.removePotionEffect(potionEffect.getType()));
            }, 5L);
        } else {
            event.getVictim().getPlayer().setGameMode(GameMode.SPECTATOR);
            boolean everyPlayerInTeamIsSpectator = true;
            for (Player p : event.getVictim().getTeam().getPlayers()) {
                if (p.getGameMode() == GameMode.SURVIVAL) everyPlayerInTeamIsSpectator = false;
            }
            if (everyPlayerInTeamIsSpectator) {
                // TODO: team lose event
                // Нужно будет написать специальный ивент, но пока просто ресетаем арену, когда у команды закончился лайфпул и все её игроки в спеке.
                event.getBattleground().broadcast(Component.text("Команда " + event.getVictim().getTeam().getName() + " проиграла.").color(TextColor.color(121, 157, 9)).decorate(TextDecoration.BOLD));
                Minespades.getPlugin(Minespades.class).getBattlegrounder().reset(event.getBattleground());
            }
        }

    }

    @EventHandler
    private void whenPlayerKillPlayer(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntity() instanceof Player player && event.getDamager() instanceof Player killer) {
            for (BattlegroundPlayer p : playerManager.getPlayersInGame()) {
                if (player.equals(p.getPlayer()) && player.getHealth() <= event.getFinalDamage()) {
                    event.setCancelled(true);
                    Bukkit.getServer().getPluginManager().callEvent(new BattlegroundPlayerDeathEvent(p.getPlayer(), killer, true, BattlegroundPlayerDeathEvent.RespawnMethod.QUICK));
                }
            }
        } else if (event.getEntity() instanceof Player player && event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player killer) {
                for (BattlegroundPlayer p : playerManager.getPlayersInGame()) {
                    if (player.equals(p.getPlayer()) && player.getHealth() <= event.getFinalDamage()) {
                        event.setCancelled(true);
                        Bukkit.getServer().getPluginManager().callEvent(new BattlegroundPlayerDeathEvent(p.getPlayer(), killer, true, BattlegroundPlayerDeathEvent.RespawnMethod.QUICK));
                    }
                }
            }
        }

    }

    @EventHandler
    private void whenPlayerShouldDie(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntity() instanceof Player player) {
            if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE)
                for (BattlegroundPlayer p : playerManager.getPlayersInGame()) {
                    if (player.equals(p.getPlayer()) && player.getHealth() <= event.getFinalDamage()) {
                        event.setCancelled(true);
                        Bukkit.getServer().getPluginManager().callEvent(new BattlegroundPlayerDeathEvent(p.getPlayer(), true, BattlegroundPlayerDeathEvent.RespawnMethod.QUICK));
                    }
                }
        }
    }

    @EventHandler
    private void whenPlayerQuitServer(PlayerQuitEvent event) {
        BattlegroundPlayer bgPlayer = Minespades.getPlugin(Minespades.class).getGameMaster().getPlayerManager().getBattlegroundPlayer(event.getPlayer());
        if (bgPlayer != null)
            Bukkit.getServer().getPluginManager().callEvent(new PlayerQuitBattlegroundEvent(bgPlayer.getBattleground(), bgPlayer.getTeam(), event.getPlayer()));
    }

    /**
     * Когда игрок подключается к арене, то его инвентарь перезаписывается лоадаутом. Дабы игроки не теряли
     * свои вещи, необходимо сохранять старый инвентарь в датабазе и загружать его, когда игрок покидает арену.
     * И не только инвентарь! Кол-во хитпоинтов, голод, координаты, активные баффы и дебаффы и т. д.
     * */
    public static class PlayerManager {

        @Getter (AccessLevel.PUBLIC)
        private final List<BattlegroundPlayer> playersInGame = new ArrayList<>();
        private final Minespades plugin = Minespades.getPlugin(Minespades.class);

        /**
         * Лёгкий способ получить обёртку игрока.
         * @return BattlegroundPlayer или null, если игрок не на арене
         * */
        public BattlegroundPlayer getBattlegroundPlayer(Player player) {
            for (BattlegroundPlayer bgPlayer : playersInGame) {
                if (bgPlayer.getPlayer().equals(player)) {
                    return bgPlayer;
                }
            }
            return null;
        }

        @SneakyThrows
        private void save(Player player) {
            try (Connection connection = connect()) {
                // Сперва убеждаемся, что в датабазе есть нужная таблица (если нет, то создаём)
                Statement statement = connection.createStatement();
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (name VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, location TEXT NOT NULL, inventory TEXT NOT NULL, health DOUBLE NOT NULL, hunger INT NOT NULL);");
                statement.close();

                PreparedStatement deleteOldValue = connection.prepareStatement("DELETE FROM players WHERE name = ?;");
                deleteOldValue.setString(1, player.getName());
                deleteOldValue.executeUpdate();

                // После создаём PreparedStatement
                PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO players (name, world, location, inventory, health, hunger) VALUES (?,?,?,?,?,?);");

                Location l = player.getLocation();
                String encodedLocation = Base64Coder.encodeString(String.format("%f; %f; %f; %f; %f", l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));

                // И заполняем его. Сохраняем имя игрока, координаты, его инвентарь, хп и голод.
                preparedStatement.setString(1, player.getName());
                preparedStatement.setString(2, player.getWorld().getName());
                preparedStatement.setString(3, encodedLocation);
                preparedStatement.setString(4, inventoryToJSONString(player.getInventory()));
                preparedStatement.setDouble(5, player.getHealth());
                preparedStatement.setDouble(6, player.getFoodLevel());
                preparedStatement.executeUpdate();

                Bukkit.getLogger().info(String.format("Инвентарь игрока %s был сохранён.", player.getName()));
            }
        }

        @SneakyThrows
        private void load(Player player) {
            try (Connection connection = connect()) {
                PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM players WHERE name = ?;"); // TODO: нужен селектор команды, добавь WHERE

                preparedStatement.setString(1, player.getName());
                ResultSet r = preparedStatement.executeQuery(); r.next();

                World world = Bukkit.getWorld(r.getString("world"));
                Location location = this.decodeLocation(world, r.getString("location"));
                Inventory inventory = this.readInventory(r.getString("inventory"));
                double health = r.getDouble("health");
                int hunger = r.getInt("hunger");

                player.teleport(location);
                player.getInventory().setContents(inventory.getContents());
                player.setHealth(health);
                player.setFoodLevel(hunger);
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage("§7Инвентарь был восстановлен.");
            }
        }

        @SneakyThrows
        private Connection connect() {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/data.sl3");
            Statement statement = connection.createStatement();
            statement.execute("PRAGMA journal_mode=OFF");
            statement.execute("PRAGMA synchronous=OFF");
            statement.close();
            return connection;
        }

        private Location decodeLocation(World world, String encoded) {
            String decoded = Base64Coder.decodeString(encoded);
            String[] split = decoded.replace(',', '.').split("; ");

            double x = Double.parseDouble(split[0]), y = Double.parseDouble(split[1]), z = Double.parseDouble(split[2]);
            float yaw = Float.parseFloat(split[3]), pitch = Float.parseFloat(split[4]);

            return new Location(world, x, y, z, yaw, pitch);
        }

        @NotNull
        private Inventory readInventory(String string) {
            JsonObject obj = JsonParser.parseString(string).getAsJsonObject();


            Inventory inv = Bukkit.createInventory(null, InventoryType.valueOf(obj.get("type").getAsString()));

            JsonArray items = obj.get("items").getAsJsonArray();
            for (JsonElement itemele: items) {
                JsonObject jitem = itemele.getAsJsonObject();
                ItemStack item = decodeItem(jitem.get("data").getAsString());
                inv.setItem(jitem.get("slot").getAsInt(), item);
            }

            return inv;
        }

        @SneakyThrows
        private ItemStack decodeItem(String base64) {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(base64));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        }

        @SneakyThrows
        private String inventoryToJSONString(PlayerInventory inventory) {
            JsonObject obj = new JsonObject();

            obj.addProperty("type", inventory.getType().name());
            obj.addProperty("size", inventory.getSize());

            JsonArray items = new JsonArray();
            for (int i = 0; i < inventory.getSize(); i++) {
                ItemStack item = inventory.getItem(i);
                if (item != null) {
                    JsonObject jitem = new JsonObject();
                    jitem.addProperty("slot", i);
                    String itemData = itemStackToBase64(item);
                    jitem.addProperty("data", itemData);
                    items.add(jitem);
                }
            }
            obj.add("items", items);
            return obj.toString();
        }

        @SneakyThrows
        protected final String itemStackToBase64(ItemStack item) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        }

    }

}