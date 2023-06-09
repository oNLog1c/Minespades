package me.nologic.minespades.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.SneakyThrows;
import me.nologic.minespades.Minespades;
import me.nologic.minespades.battleground.Battleground;
import me.nologic.minespades.battleground.BattlegroundPlayer;
import me.nologic.minespades.battleground.BattlegroundPreferences;
import me.nologic.minespades.battleground.BattlegroundPreferences.Preference;
import me.nologic.minespades.battleground.BattlegroundTeam;
import me.nologic.minespades.game.event.*;
import me.nologic.minespades.util.Colorizable;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EventDrivenGameMaster implements Listener {

    private final @Getter BattlegroundPlayerManager playerManager = new BattlegroundPlayerManager();
    private final @Getter PlayerKDAHandler          playerKDA = new PlayerKDAHandler();

    @EventHandler
    private void onPlayerQuitBattleground(PlayerQuitBattlegroundEvent event) {
        BattlegroundPlayer battlegroundPlayer = playerManager.getBattlegroundPlayer(event.getPlayer());
        if (battlegroundPlayer != null) {
            this.playerManager.disconnect(battlegroundPlayer);
        }
    }

    @EventHandler
    private void onBattlegroundSuccessfulLoad(BattlegroundSuccessfulLoadEvent event) {
        Battleground battleground = event.getBattleground();
        // Если арена является частью мультиграунда, то вместо настоящего названия арены мы используем название мультиграунда
        final String name = battleground.getPreference(BattlegroundPreferences.Preference.IS_MULTIGROUND) ? battleground.getMultiground().getName() : battleground.getBattlegroundName();

        // TODO: Добавить игрокам возможность отказываться от авто-коннекта
        if (battleground.getPreference(Preference.FORCE_AUTOJOIN)) {
            Bukkit.getOnlinePlayers().forEach(p -> {
                p.sendMessage("§7Вы были автоматически подключены к арене. Чтобы покинуть арену, напишите §3/ms q§7.");
                Bukkit.getServer().getPluginManager().callEvent(new PlayerEnterBattlegroundEvent(battleground, battleground.getSmallestTeam(), p));
            });
        }

        Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_6, 1F, 1F));

        Minespades.getInstance().broadcast(Component.text("На арене " + name + " начинается новая битва!").color(TextColor.color(180, 63, 61)));
        Minespades.getInstance().broadcast(Component.text("Кликни, чтобы подключиться: ").color(TextColor.color(180, 63, 61))
                .append(Component.text("/ms join " + name).clickEvent(ClickEvent.suggestCommand("/ms join " + name)).color(TextColor.color(182, 48, 41)).decorate(TextDecoration.UNDERLINED)));
    }

    @EventHandler
    private void onBattlegroundPlayerDeath(BattlegroundPlayerDeathEvent event) {

        final Player player = event.getVictim().getBukkitPlayer();

        this.playerKDA.handlePlayerDeath(event);

        // Довольно простая механика лайфпулов. После смерти игрока лайфпул команды уменьшается.
        // Если игрок умер, а очков жизней больше нет — игрок становится спектатором.
        // Если в команде умершего игрока все игроки в спеке, то значит команда проиграла.
        int lifepool = event.getVictim().getTeam().getLifepool();

        if (lifepool >= 1) {
            event.getVictim().getTeam().setLifepool(lifepool - 1);

            if (!event.isKeepInventory()) {
                event.getVictim().setRandomLoadout();
            }

            // Если не сделать задержку в 1 тик, то некоторые изменения состояния игрока не применятся (fireTicks, tp)
            Bukkit.getScheduler().runTaskLater(playerManager.plugin, () -> {

                if (event.getVictim().isCarryingFlag())
                    event.getVictim().getFlag().drop();

                switch (event.getRespawnMethod()) {
                    case QUICK -> player.teleport(event.getVictim().getTeam().getRandomRespawnLocation());
                    case AOS -> player.sendMessage("не реализовано...");
                    case NORMAL -> player.sendMessage("lol ok");
                }
                player.setNoDamageTicks(40);
                player.setHealth(20);
                player.setFoodLevel(20);
                player.setFireTicks(0);
                player.getActivePotionEffects().forEach(potionEffect -> player.removePotionEffect(potionEffect.getType()));
            }, 1L);
        } else {
            if (event.getVictim().isCarryingFlag())
                event.getVictim().getFlag().drop();
            event.getVictim().getBukkitPlayer().setGameMode(GameMode.SPECTATOR);
            boolean everyPlayerInTeamIsSpectator = true;
            for (Player p : event.getVictim().getTeam().getPlayers()) {
                if (p.getGameMode() == GameMode.SURVIVAL) {
                    everyPlayerInTeamIsSpectator = false;
                    break;
                }
            }
            if (everyPlayerInTeamIsSpectator)
                Bukkit.getServer().getPluginManager().callEvent(new BattlegroundTeamLoseEvent(event.getBattleground(), event.getVictim().getTeam()));
        }

    }

    @EventHandler
    private void onPlayerCarriedFlagEvent(PlayerCarriedFlagEvent event) {

        BattlegroundTeam team = event.getFlag().getTeam();

        TextComponent flagCarriedMessage = Component.text("").append(Component.text(event.getPlayer().getBukkitPlayer().getDisplayName()))
                .append(Component.text(" приносит флаг команды "))
                .append(team.getDisplayName())
                .append(Component.text(" на свою базу!"));

        TextComponent lifepoolMessage = Component.text("Команда ").append(team.getDisplayName())
                .append(Component.text(" теряет " + team.getFlagLifepoolPenalty() + " жизней!"));

        event.getBattleground().broadcast(flagCarriedMessage);
        event.getBattleground().broadcast(lifepoolMessage);

        event.getFlag().reset();
        team.setLifepool(team.getLifepool() - team.getFlagLifepoolPenalty());
        event.getPlayer().setKills(event.getPlayer().getKills() + team.getFlagLifepoolPenalty());
        event.getPlayer().getBukkitPlayer().setGlowing(false);
    }

    @EventHandler
    private void onBattlegroundTeamLose(BattlegroundTeamLoseEvent event) {
        TextComponent message = Component.text("Команда ").append(event.getTeam().getDisplayName()).append(Component.text(" проиграла!"));
        event.getBattleground().broadcast(message);

        // Если на арене осталась только одна непроигравшая команда, то игра считается оконченой
        if (event.getBattleground().getTeams().stream().filter(t -> !t.isDefeated()).count() <= 1) {
            Bukkit.getServer().getPluginManager().callEvent(new BattlegroundGameOverEvent(event.getBattleground()));
        }
    }

    /* Проигрыш команды. */
    @EventHandler
    private void onBattlegroundGameOver(BattlegroundGameOverEvent event) {
        final Battleground battleground = event.getBattleground();
        if (battleground.getPreference(Preference.IS_MULTIGROUND)) {
            Minespades.getPlugin(Minespades.class).getBattlegrounder().disable(event.getBattleground());
            battleground.getMultiground().launchNextInOrder();
            return;
        }
        Minespades.getPlugin(Minespades.class).getBattlegrounder().reset(event.getBattleground());
    }

    @EventHandler
    private void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!event.isCancelled() && event.getDamager() instanceof Player killer && event.getEntity() instanceof Player victim) {
            if (BattlegroundPlayer.getBattlegroundPlayer(victim) != null) {
                if (victim.getHealth() <= event.getFinalDamage()) {
                    EntityDamageEvent.DamageCause cause = event.getCause();
                    BattlegroundPlayerDeathEvent bpde = new BattlegroundPlayerDeathEvent(victim, killer, cause,true, BattlegroundPlayerDeathEvent.RespawnMethod.QUICK);
                    Bukkit.getServer().getPluginManager().callEvent(bpde);
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    private void onPlayerDamage(EntityDamageEvent event) {
        if (!event.isCancelled() && event.getEntity() instanceof Player player && player.getHealth() <= event.getFinalDamage() && player.getLastDamageCause() != null) {

            switch (player.getLastDamageCause().getCause()) {
                case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, MAGIC, THORNS: return;
            }

            BattlegroundPlayerDeathEvent bpde = new BattlegroundPlayerDeathEvent(player, null, event.getCause(),true, BattlegroundPlayerDeathEvent.RespawnMethod.QUICK);
            Bukkit.getServer().getPluginManager().callEvent(bpde);
            event.setCancelled(true);
        }
    }


    @EventHandler // Отмена телепортации на арене в режиме наблюдателя
    private void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            BattlegroundPlayer bgPlayer = Minespades.getPlugin(Minespades.class).getGameMaster().getPlayerManager().getBattlegroundPlayer(event.getPlayer());
            if (bgPlayer != null && event.getTo() != null) {
                if (!Objects.equals(event.getTo().getWorld(), bgPlayer.getBattleground().getWorld())) {
                    event.setCancelled(true);
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
    public static class BattlegroundPlayerManager implements Colorizable {

        @Getter
        private final List<BattlegroundPlayer> playersInGame = new ArrayList<>();
        private final Minespades plugin = Minespades.getPlugin(Minespades.class);

        /**
         * Лёгкий способ получить обёртку игрока.
         * @return BattlegroundPlayer или null, если игрок не на арене
         * */
        public BattlegroundPlayer getBattlegroundPlayer(Player player) {
            for (BattlegroundPlayer bgPlayer : playersInGame) {
                if (bgPlayer.getBukkitPlayer().equals(player)) {
                    return bgPlayer;
                }
            }
            return null;
        }

        /** Подключает игроков к баттлграунду. */
        public BattlegroundPlayer connect(Player player, Battleground battleground, BattlegroundTeam team) {
            if (battleground.isValid() && this.getBattlegroundPlayer(player) == null) {
                this.save(player);
                BattlegroundPlayer bgPlayer = battleground.connectPlayer(player, team);
                this.getPlayersInGame().add(bgPlayer);

                Audience audience = plugin.getAdventureAPI().player(player);
                final String name = this.translateColors(team.getColor().asHexString() + player.getName());
                player.setDisplayName(name);
                player.setPlayerListName(name);
                player.setHealth(20);
                player.setFoodLevel(20);
                player.getActivePotionEffects().forEach(potionEffect -> player.removePotionEffect(potionEffect.getType()));
                bgPlayer.showSidebar();

                for (BattlegroundTeam t : battleground.getTeams()) {
                    if (t.getFlag() != null && t.getFlag().getRecoveryBossBar() != null) {
                        audience.showBossBar(t.getFlag().getRecoveryBossBar());
                    }
                }

                // Попробуем отправить сообщение об успешном подключении через тайтл..
                Title title = Title.title(Component.text("Подключение успешно!").color(TextColor.color(162, 9, 78)),
                        Component.text("Ваша команда: ").append(team.getDisplayName().decorate(TextDecoration.BOLD)),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(4000), Duration.ofMillis(500))
                );
                audience.showTitle(title);

                // PlayerEnterBattlegroundEvent вызывается когда игрок уже присоединился к арене, получил вещи и был телепортирован.
                Bukkit.getServer().getPluginManager().callEvent(new PlayerEnterBattlegroundEvent(battleground, team, player));
                return bgPlayer;
            } else {
                player.sendMessage("§4Подключение неудачно. Арена отключена или вы уже в игре.");
                return null;
            }
        }

        // TODO: УЛУЧШИ БЛЯДЬ ЭТО ГОВНО
        /** Отключает игрока от баттлграунда. */
        public void disconnect(@NotNull BattlegroundPlayer battlegroundPlayer) {

            Player player = battlegroundPlayer.getBukkitPlayer();
            Audience audience = plugin.getAdventureAPI().player(player);

            if (battlegroundPlayer.isCarryingFlag()) {
                battlegroundPlayer.getFlag().drop();
            }

            battlegroundPlayer.removeSidebar();
            for (BattlegroundTeam team : battlegroundPlayer.getBattleground().getTeams()) {
                if (team.getFlag() != null && team.getFlag().getRecoveryBossBar() != null) {
                    audience.hideBossBar(team.getFlag().getRecoveryBossBar());
                }
            }

            this.getPlayersInGame().remove(battlegroundPlayer);
            battlegroundPlayer.getBattleground().kick(battlegroundPlayer);
            this.load(player);

            final String name = ChatColor.WHITE + player.getName();
            player.setDisplayName(name);
            player.setPlayerListName(name);

            // Проверяем игроков на спектаторов. Если в команде начали появляться спектаторы, то
            // значит у неё закончились жизни. Если последний живой игрок ливнёт, а мы не обработаем
            // событие выхода, то игра встанет. Поэтому нужно всегда проверять команду.
            if (battlegroundPlayer.getTeam().getLifepool() == 0 && battlegroundPlayer.getTeam().getPlayers().size() > 1) {
                boolean everyPlayerInTeamIsSpectator = true;
                for (Player p : battlegroundPlayer.getTeam().getPlayers()) {
                    if (p.getGameMode() == GameMode.SURVIVAL) {
                        everyPlayerInTeamIsSpectator = false;
                        break;
                    }
                }
                if (everyPlayerInTeamIsSpectator)
                    Bukkit.getServer().getPluginManager().callEvent(new BattlegroundTeamLoseEvent(battlegroundPlayer.getBattleground(), battlegroundPlayer.getTeam()));
            }
        }

        /**
         * Сохранение состояния указанного игрока: в датабазе сохраняется инвентарь, координаты, здоровье и голод.
         */
        @SneakyThrows
        private void save(Player player) {
            try (Connection connection = connect()) {

                // Сперва убеждаемся, что в датабазе есть нужная таблица (если нет, то создаём)
                String sql = "CREATE TABLE IF NOT EXISTS players (name VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, location TEXT NOT NULL, inventory TEXT NOT NULL, health DOUBLE NOT NULL, hunger INT NOT NULL);";
                connection.createStatement().executeUpdate(sql);

                // С целью избежания багов и путанницы, удаляем старое значение
                PreparedStatement deleteOldValue = connection.prepareStatement("DELETE FROM players WHERE name = ?;");
                deleteOldValue.setString(1, player.getName());
                deleteOldValue.executeUpdate();

                // Готовимся сохранить данные игрока в датабазе (имя, мир, локация в Base64, инвентарь в JSON, здоровье, голод)
                PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO players (name, world, location, inventory, health, hunger) VALUES (?,?,?,?,?,?);");

                Location l = player.getLocation();
                String encodedLocation = Base64Coder.encodeString(String.format("%f; %f; %f; %f; %f", l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));

                preparedStatement.setString(1, player.getName());
                preparedStatement.setString(2, player.getWorld().getName());
                preparedStatement.setString(3, encodedLocation);
                preparedStatement.setString(4, inventoryToJSONString(player.getInventory()));
                preparedStatement.setDouble(5, player.getHealth());
                preparedStatement.setDouble(6, player.getFoodLevel());
                preparedStatement.executeUpdate();

                plugin.getLogger().info(String.format("Инвентарь игрока %s был сохранён.", player.getName()));
            }
        }

        @SneakyThrows
        public void load(Player player) {
            try (Connection connection = connect()) {
                PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM players WHERE name = ?;");
                preparedStatement.setString(1, player.getName());
                ResultSet r = preparedStatement.executeQuery(); r.next();

                World     world     = Bukkit.getWorld(r.getString("world"));
                Location  location  = this.decodeLocation(world, r.getString("location"));
                Inventory inventory = this.parseJsonToInventory(r.getString("inventory"));
                double    health    = r.getDouble("health");
                int       hunger    = r.getInt("hunger");

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
            return DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/player.db");
        }

        private Location decodeLocation(World world, String encoded) {
            String decoded = Base64Coder.decodeString(encoded);
            String[] split = decoded.replace(',', '.').split("; ");

            double x = Double.parseDouble(split[0]), y = Double.parseDouble(split[1]), z = Double.parseDouble(split[2]);
            float yaw = Float.parseFloat(split[3]), pitch = Float.parseFloat(split[4]);

            return new Location(world, x, y, z, yaw, pitch);
        }

        @NotNull
        private Inventory parseJsonToInventory(String string) {
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