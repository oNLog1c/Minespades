package me.nologic.minespades.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import lombok.SneakyThrows;
import me.nologic.minespades.BattlegroundManager;
import me.nologic.minespades.Minespades;
import me.nologic.minespades.battleground.*;
import me.nologic.minespades.battleground.BattlegroundPreferences.Preference;
import me.nologic.minespades.battleground.editor.PlayerEditSession;
import me.nologic.minespades.game.event.PlayerQuitBattlegroundEvent;
import me.nologic.minority.MinorityFeature;
import me.nologic.minority.annotations.Translatable;
import me.nologic.minority.annotations.TranslationKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;

@Translatable
@CommandAlias("minespades|ms")
@CommandPermission("minespades.player")
public class MinespadesCommand extends BaseCommand implements MinorityFeature {

    private final Minespades          plugin;
    private final CommandCompletions  completions;
    private final BattlegroundManager battlegrounder;

    public MinespadesCommand(final Minespades plugin) {
        this.init(this, this.getClass(), Minespades.getInstance());
        this.plugin = plugin;
        this.battlegrounder = plugin.getBattlegrounder();
        this.completions = new CommandCompletions();
        this.registerCompletions();
    }

    private void registerCompletions() {
        plugin.getCommandManager().getCommandCompletions().registerCompletion("enabledBattlegrounds", c -> completions.getEnabledBattlegrounds());
        plugin.getCommandManager().getCommandCompletions().registerCompletion("battlegrounds", c -> completions.getBattlegroundFileList());
        plugin.getCommandManager().getCommandCompletions().registerCompletion("loadouts", c -> completions.getTargetTeamLoadouts(c.getPlayer()));
        plugin.getCommandManager().getCommandCompletions().registerCompletion("battlegroundPreferences", c -> completions.getBattlegroundPreferences());
        plugin.getCommandManager().getCommandCompletions().registerCompletion("battlegroundTeamsOnJoin", c -> completions.getBattlegroundTeamsOnJoinCommand(c.getPlayer(), c.getContextValue(String.class, 1)));
        plugin.getCommandManager().getCommandCompletions().registerCompletion("teams", c -> completions.getTargetedBattlegroundTeams(c.getPlayer()));
        plugin.getCommandManager().getCommandCompletions().registerCompletion("supplies", c -> completions.getTargetTeamSupplies(c.getPlayer()));
    }

    @TranslationKey(section = "editor-error-messages", name = "battleground-is-multiground", value = "Error. This battleground is configured as part of a multiground.")
    private String isMultigroundMessage;

    @Subcommand("launch")
    @CommandCompletion("@battlegrounds")
    @CommandPermission("minespades.editor")
    public void onLaunch(Player player, String name) {
        name = name.toLowerCase();
        if (battlegrounder.getValidator().isValid(player, name)) {
            if (!battlegrounder.enable(name)) {
                player.sendMessage(isMultigroundMessage);
            }
        }
    }

    @TranslationKey(section = "editor-info-messages", name = "preference-changed", value = "§2Success§r. §6%s §ris changed to §3%s§r.")
    private String parameterChangedMessage;

    @Subcommand("config")
    @CommandCompletion("@battlegroundPreferences")
    @CommandPermission("minespades.editor")
    public void onConfig(Player player, String preference, boolean value) {
        if (validated(player, Selection.BATTLEGROUND)) {
            var preferences = Battleground.getPreferences(battlegrounder.getEditor().editSession(player).getTargetBattleground());
            if (Preference.isValid(preference)) {
                preferences.set(Preference.valueOf(preference), value);
                player.sendMessage(String.format(parameterChangedMessage, preference, value));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1F, 0F);
            }
        }
    }

    @TranslationKey(section = "editor-error-messages", name = "no-banner-in-hand", value = "Error. Hold the banner you want to be the flag.")
    private String holdTheBannerMessage;

    @TranslationKey(section = "editor-error-messages", name = "join-while-editing", value = "Error. You can't join games while in editor mode. Use §3/ms edit §ragain to close editor.")
    private String joinWhileEditingMessage;

    @TranslationKey(section = "editor-error-messages", name = "edit-while-playing", value = "Error. You can't edit battlegrounds while playing.")
    private String editingWhilePlaying;

    @Subcommand("add")
    @CommandPermission("minespades.editor")
    public class Add extends BaseCommand {

        @Subcommand("respawn")
        public void onAddRespawn(Player player) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM)) {
                battlegrounder.getEditor().addRespawnPoint(player);
            }
        }

        @Subcommand("loadout")
        public void onAddLoadout(Player player, String name) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM)) {
                battlegrounder.getEditor().addLoadout(player, name);
            }
        }

        @Subcommand("supply")
        public void onAddSupply(Player player, String name, int interval, int amount, int maximum, String permission) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM, Selection.LOADOUT)) {
                battlegrounder.getEditor().addSupply(player, name, interval, amount, maximum, permission);
            }
        }

        @Subcommand("flag")
        public void onAddFlag(Player player) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM)) {

                if (!player.getInventory().getItemInMainHand().getType().toString().contains("BANNER")) {
                    player.sendMessage(holdTheBannerMessage);
                    return;
                }

                battlegrounder.getEditor().addFlag(player);
            }
        }

    }

    @TranslationKey(section = "editor-info-messages", name = "multiground-created", value = "§2Success§r. Multiground §3%s §rhas been created.")
    private String multigroundCreatedMessage;

    @TranslationKey(section = "editor-info-messages", name = "battleground-deleted-from-multiground", value = "§2Success§r. Battleground §3%s §rhas been removed from multiground §3%s§r.")
    private String battlegroundDeletedFromMultiground;

    @TranslationKey(section = "editor-info-messages", name = "multiground-battleground-added", value = "§2Success§r. Battleground §3%s §rnow is part of §3%s §rmultiground.")
    private String battlegroundAddedToMultigroundMessage;

    @TranslationKey(section = "editor-error-messages", name = "battleground-is-part-of-multiground", value = "§4Error§r. Battleground §3%s §ris already part of some multiground.")
    private String battlegroundIsPartOfMultigroundMessage;

    @TranslationKey(section = "editor-error-messages", name = "multiground-already-launched", value = "§4Error§r. Multiground §3%s §ralready launched.")
    private String multigroundAlreadyLaunchedMessage;

    @TranslationKey(section = "editor-error-messages", name = "multiground-name-is-taken", value = "§4Error§r. Multiground with name §3%s §ralready exist.")
    private String multigroundNameIsTakenMessage;

    @TranslationKey(section = "editor-error-messages", name = "multiground-does-not-exist", value = "§4Error§r. Multiground with name §3%s §rdoesn't exist.")
    private String notExistingMultigroundMessage;

    @TranslationKey(section = "editor-error-messages", name = "multiground-does-not-have-battleground", value = "§4Error§r. Multiground §3%s §rdoesn't have battleground §3%s§r.")
    private String battlegroundNotFoundMessage;

    @Subcommand("multiground")
    @CommandPermission("minespades.editor")
    public class MultigroundCommand extends BaseCommand {

        @Subcommand("create") @SneakyThrows
        public void onMultigroundCreate(Player player, String multigroundName) {
            multigroundName = multigroundName.toLowerCase();
            File path = new File(plugin.getDataFolder(), "multigrounds.yml");
            if (path.createNewFile()) plugin.getLogger().info("Creating multigrounds.yml...");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path);
            if (yaml.getConfigurationSection(multigroundName) == null) {
                yaml.createSection(multigroundName);
                yaml.save(path);
                player.sendMessage(String.format(multigroundCreatedMessage, multigroundName));
            } else player.sendMessage(String.format(multigroundNameIsTakenMessage, multigroundName));
        }

        @Subcommand("add") @SneakyThrows
        public void onMultigroundAdd(Player player, String multigroundName, String battlegroundName) {
            File path = new File(plugin.getDataFolder(), "multigrounds.yml");
            if (path.createNewFile()) plugin.getLogger().info("Creating multigrounds.yml...");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path);
            ConfigurationSection section = yaml.getConfigurationSection(multigroundName);
            if (section == null) {
                player.sendMessage(String.format(notExistingMultigroundMessage, multigroundName));
                return;
            }
            if (battlegrounder.getValidator().isValid(player, battlegroundName)) {
                final BattlegroundPreferences preferences = BattlegroundPreferences.loadPreferences(new Battleground(battlegroundName));

                // Проверяем баттлграунд на мультиграундность
                if (preferences.get(Preference.IS_MULTIGROUND)) {
                    player.sendMessage(String.format(battlegroundIsPartOfMultigroundMessage, battlegroundName));
                    return;
                }

                final List<String> battlegrounds = section.getStringList("battlegrounds");
                battlegrounds.add(battlegroundName);
                section.set("battlegrounds", battlegrounds);
                yaml.save(path);

                // Имеет смысл автоматически менять значение IS_MULTIGROUND на true
                preferences.set(Preference.IS_MULTIGROUND, true);
                player.sendMessage(String.format(battlegroundAddedToMultigroundMessage, battlegroundName, multigroundName));
            }
        }

        @Subcommand("remove") @SneakyThrows
        public void onMultigroundRemove(Player player, String multigroundName, String battlegroundName) {
            File path = new File(plugin.getDataFolder(), "multigrounds.yml");
            if (path.createNewFile()) plugin.getLogger().info("Creating multigrounds.yml...");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path);
            ConfigurationSection section = yaml.getConfigurationSection(multigroundName);
            if (section == null) {
                player.sendMessage(String.format(notExistingMultigroundMessage, multigroundName));
                return;
            }
            if (section.getStringList("battlegrounds").contains(battlegroundName)) {
                List<String> battlegrounds = section.getStringList("battlegrounds");
                battlegrounds.remove(battlegroundName);
                section.set("battlegrounds", battlegrounds);
                yaml.save(path);
                BattlegroundPreferences.loadPreferences(new Battleground(battlegroundName)).set(Preference.IS_MULTIGROUND, false);
                player.sendMessage(String.format(battlegroundDeletedFromMultiground, battlegroundName, multigroundName));
            } else player.sendMessage(String.format(battlegroundNotFoundMessage, multigroundName, battlegroundName));
        }

        @Subcommand("launch") @SneakyThrows
        public void onMultigroundLaunch(Player player, String multigroundName) {
            File path = new File(plugin.getDataFolder(), "multigrounds.yml");
            if (path.createNewFile()) plugin.getLogger().info("Creating multigrounds.yml...");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path);
            ConfigurationSection section = yaml.getConfigurationSection(multigroundName);
            if (section != null) {
                if (plugin.getBattlegrounder().getMultigrounds().stream().noneMatch(m -> m.getName().equals(multigroundName))) {
                    plugin.getBattlegrounder().launchMultiground(multigroundName, section.getStringList("battlegrounds"));
                } else player.sendMessage(String.format(multigroundAlreadyLaunchedMessage, multigroundName));
            } else player.sendMessage(String.format(notExistingMultigroundMessage, multigroundName));
        }

    }

    @Subcommand("create")
    @CommandPermission("minespades.editor")
    public class Create extends BaseCommand {

        @Subcommand("battleground")
        public void onCreateBattleground(Player player, String name) {
            name = name.toLowerCase();
            if (battlegrounder.getValidator().isBattlegroundExist(name))  {
                player.sendMessage(String.format(battlegroundNameIsTakenMessage, name));
                return;
            }
            battlegrounder.getEditor().create(player, name);
        }

        @Subcommand("team")
        public void onCreateTeam(Player player, String teamName) {
            if (validated(player, Selection.BATTLEGROUND)) {
                battlegrounder.getEditor().createTeam(player, teamName);
            }
        }

    }

    @Subcommand("edit")
    @CommandPermission("minespades.editor")
    public void onEdit(final Player player) {
        if (BattlegroundPlayer.getBattlegroundPlayer(player) != null) {
            player.sendMessage(editingWhilePlaying);
            return;
        }
        final PlayerEditSession session = battlegrounder.getEditor().editSession(player);
        session.setActive(!session.isActive());
    }

    @TranslationKey(section = "editor-info-messages", name = "how-to-select-corners", value = "Hold a golden sword and use §lRMB/LMB §rto select two corners, then use §3/ms save §rto save the volume.")
    private String howToSelectCornersMessage;

    @TranslationKey(section = "editor-info-messages", name = "team-color-updated", value = "Team §3%s §rnew color: ")
    private String teamColorUpdatedMessage;

    @Subcommand("remove")
    @CommandPermission("minespades.editor")
    private class Remove extends BaseCommand {

        @Subcommand("flag")
        public void onRemoveFlag(final Player player) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM) && battlegrounder.getValidator().isTeamHaveFlag(player, battlegrounder.getEditor().editSession(player).getTargetTeam())) {
                battlegrounder.getEditor().removeFlag(player, battlegrounder.getEditor().editSession(player).getTargetTeam());
            }
        }

        @Subcommand("loadout")
        @CommandCompletion("@loadouts")
        public void onRemoveLoadout(final Player player, final String loadoutName) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM) && battlegrounder.getValidator().isLoadoutExist(player, loadoutName)) {
                battlegrounder.getEditor().removeLoadout(player, loadoutName);
            }
        }

        @Subcommand("team")
        @CommandCompletion("@teams")
        public void onRemoveTeam(final Player player, final String teamName) {
            if (validated(player, Selection.BATTLEGROUND) && battlegrounder.getValidator().isTeamExist(player, teamName)) {
                battlegrounder.getEditor().removeTeam(player, teamName);
            }
        }

        @Subcommand("supply")
        @CommandCompletion("@supplies")
        public void onRemoveSupply(final Player player, final String supplyName) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM, Selection.LOADOUT) && battlegrounder.getValidator().isSupplyExist(player, supplyName)) {
                battlegrounder.getEditor().removeSupply(player, supplyName);
            }
        }

    }

    @Subcommand("edit")
    @CommandPermission("minespades.editor")
    public class Edit extends BaseCommand {

        @Subcommand("battleground")
        @CommandCompletion("@battlegrounds")
        public void onEditBattleground(Player player, String name) {
            if (BattlegroundPlayer.getBattlegroundPlayer(player) != null) {
                player.sendMessage(editingWhilePlaying);
                return;
            }
            final PlayerEditSession session = battlegrounder.getEditor().editSession(player);
            name = name.toLowerCase();
            if (battlegrounder.getValidator().isBattlegroundExist(name)) {
                battlegrounder.getEditor().editSession(player).setTargetLoadout(null);
                battlegrounder.getEditor().editSession(player).setTargetTeam(null);
                battlegrounder.getEditor().editSession(player).resetCorners();
                battlegrounder.getEditor().editSession(player).setTargetBattleground(name);
                if (!session.isActive()) session.setActive(true);
            } else player.sendMessage(String.format(battlegroundNotExistMessage, name));
        }

        @Subcommand("volume")
        public void onEditBattlegroundVolume(Player player) {
            if (validated(player, Selection.BATTLEGROUND)) {
                battlegrounder.getEditor().editSession(player).setVolumeEditor(true);
                player.playSound(player, Sound.ENTITY_EGG_THROW, 1, 1);
                player.sendMessage(howToSelectCornersMessage);
            }
        }

        @Subcommand("team")
        @CommandCompletion("@teams")
        public void onEditTeam(Player player, String teamName) {
            if (validated(player, Selection.BATTLEGROUND) && battlegrounder.getValidator().isTeamExist(player, teamName)) {
                battlegrounder.getEditor().editSession(player).setTargetLoadout(null);
                battlegrounder.getEditor().setTargetTeam(player, teamName);
            }
        }

        @Subcommand("color")
        @CommandCompletion("FFFFFF")
        public void onEditColor(Player player, String hexColor) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM)) {
                battlegrounder.getEditor().setTeamColor(player, hexColor);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1F, 0F);

                final String team = battlegrounder.getEditor().editSession(player).getTargetTeam();
                plugin.getAdventureAPI().player(player).sendMessage(Component.text(String.format(teamColorUpdatedMessage, team))
                        .append(Component.text(hexColor).color(TextColor.fromHexString("#" + hexColor))));
            }
        }

        @Subcommand("lifepool")
        @CommandCompletion("100")
        public void onEditLifepool(Player player, int lifepool) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM)) {
                battlegrounder.getEditor().setTargetTeamLifepool(player, lifepool);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1F, 0F);
            }
        }

        @Subcommand("loadout")
        @CommandCompletion("@loadouts")
        public void onEditLoadout(Player player, String loadoutName) {
            if (validated(player, Selection.BATTLEGROUND, Selection.TEAM) && battlegrounder.getValidator().isLoadoutExist(player, loadoutName)) {
                battlegrounder.getEditor().editSession(player).setTargetLoadout(loadoutName);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1F, 0F);
            }

        }

    }

    @Subcommand("save")
    @CommandPermission("minespades.editor")
    public void onSave(Player player) {
        if (validated(player, Selection.BATTLEGROUND)) {
            battlegrounder.getEditor().saveVolume(player);
        }
    }

    @Subcommand("join")
    @CommandCompletion("@enabledBattlegrounds @battlegroundTeamsOnJoin")
    public void onJoin(Player player, String battlegroundName, @Optional String targetTeamName) {
        battlegroundName = battlegroundName.toLowerCase();

        if (battlegrounder.getEditor().editSession(player).isActive()) {
            player.sendMessage(joinWhileEditingMessage);
            return;
        }

        // Проверка на мультиграунд
        Multiground multiground = battlegrounder.getMultiground(battlegroundName);
        if (multiground != null) {
            BattlegroundTeam targetTeam = multiground.getBattleground().getTeamByName(targetTeamName);
            Battleground battleground = multiground.getBattleground();
            multiground.connect(player, targetTeam != null && !battleground.getPreference(Preference.FORCE_AUTO_ASSIGN) ? targetTeam : battleground.getSmallestTeam());
            return;
        }

        Battleground battleground = battlegrounder.getBattlegroundByName(battlegroundName);
        if (battleground == null) {
            player.sendMessage(String.format(battlegroundNotExistMessage, battlegroundName));
            return;
        }

        if (battleground.getPreferences().get(BattlegroundPreferences.Preference.IS_MULTIGROUND)) {
            player.sendMessage(isMultigroundMessage);
            return;
        }

        // Если targetTeam != null, то подключаем плеера к указанной команде
        // Если включено принудительное автораспределение, то кидаем игрока в команду с наименьшим кол-вом игроков
        BattlegroundTeam team = targetTeamName != null && !battleground.getPreference(Preference.FORCE_AUTO_ASSIGN) ? battleground.getTeamByName(targetTeamName) : battleground.getSmallestTeam();
        plugin.getGameMaster().getPlayerManager().connect(player, battleground, team);
    }

    @TranslationKey(section = "regular-messages", name = "not-in-the-game", value = "You're not in game.")
    private String notInGameMessage;

    @Subcommand("quit|leave|q")
    public void onQuit(Player player) {
        BattlegroundPlayer bgPlayer = Minespades.getPlugin(Minespades.class).getGameMaster().getPlayerManager().getBattlegroundPlayer(player);
        if (bgPlayer != null)
            Bukkit.getServer().getPluginManager().callEvent(new PlayerQuitBattlegroundEvent(bgPlayer.getBattleground(), bgPlayer.getTeam(), player));
        else player.sendMessage(notInGameMessage);
    }

    @TranslationKey(section = "regular-messages", name = "battleground-force-reset", value = "Battleground §l%s §rwas forcefully reset by §3%s§r.")
    private String battlegroundForceResetMessage;

    @Subcommand("reset")
    @CommandCompletion("@battlegrounds")
    @CommandPermission("minespades.editor")
    public void onForceReset(Player player, String name) {
        Battleground battleground = battlegrounder.getBattlegroundByName(name);

        if (battleground == null) {
            player.sendMessage(String.format(battlegroundNotExistMessage, name));
            return;
        }

        if (battleground.getPreference(Preference.IS_MULTIGROUND)) {
            player.sendMessage(isMultigroundMessage);
            return;
        }

        battleground.broadcast(String.format(battlegroundForceResetMessage, name, player.getName()));
        battleground.getPlayers().stream().toList().forEach(bgPlayer -> Bukkit.getServer().getPluginManager().callEvent(new PlayerQuitBattlegroundEvent(bgPlayer.getBattleground(), bgPlayer.getTeam(), player)));
        battlegrounder.reset(battleground);
    }

    @TranslationKey(section = "editor-error-messages", name = "battleground-not-selected", value = "Error. No battleground selected for editing.")
    private String battlegroundNotSelectedMessage;

    @TranslationKey(section = "editor-error-messages", name = "team-not-selected", value = "Error. No team selected for editing.")
    private String teamNotSelectedMessage;

    @TranslationKey(section = "editor-error-messages", name = "loadout-not-selected", value = "Error. No loadout selected for editing.")
    private String loadoutNotSelectedMessage;

    @TranslationKey(section = "editor-error-messages", name = "inactive-edit-session", value = "Error. Edit session is inactive. Select battleground first.")
    private String noActiveSessionMessage;

    @TranslationKey(section = "editor-error-messages", name = "battleground-name-is-taken", value = "Error. Battleground with name §3%s §ris already exist.")
    private String battlegroundNameIsTakenMessage;

    @TranslationKey(section = "editor-error-messages", name = "battleground-not-exist", value = "Error. Battleground with name §3%s §rdoesn't exist.")
    private String battlegroundNotExistMessage;

    /**
     * Smart check for nulls.
     * @param player the player whose session will be validated
     */
    protected boolean validated(final Player player, final Selection... selections) {
        final PlayerEditSession session = this.battlegrounder.getEditor().editSession(player);
        for (Selection selection : selections) {
            switch (selection) {

                case SESSION -> {
                    if (!session.isActive()) {
                        player.sendMessage(noActiveSessionMessage);
                        return false;
                    }
                }

                case BATTLEGROUND -> {
                    if (!session.isBattlegroundSelected()) {
                        player.sendMessage(battlegroundNotSelectedMessage);
                        return false;
                    }
                }

                case TEAM -> {
                    if (!session.isTeamSelected()) {
                        player.sendMessage(teamNotSelectedMessage);
                        return false;
                    }
                }

                case LOADOUT -> {
                    if (!session.isLoadoutSelected()) {
                        player.sendMessage(loadoutNotSelectedMessage);
                        return false;
                    }
                }

            }
        }
        return true;
    }

    private enum Selection {
        SESSION, BATTLEGROUND, TEAM, LOADOUT
    }

}