package me.nologic.minespades;

import lombok.Getter;
import me.nologic.minespades.battleground.*;
import me.nologic.minespades.battleground.builder.BattlegroundBuilder;
import me.nologic.minespades.battleground.editor.BattlegroundEditor;
import me.nologic.minespades.battleground.editor.loadout.BattlegroundLoadout;
import me.nologic.minespades.battleground.util.BattlegroundValidator;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;

public class BattlegroundManager {

    private final HashMap<String, Battleground> enabledBattlegrounds;
    private final HashMap<String, Multiground>  enabledMultigrounds;

    private final BattlegroundBuilder builder;
    private final @Getter BattlegroundValidator validator;
    private final @Getter BattlegroundEditor    editor;

    private final Minespades plugin;

    public BattlegroundManager(Minespades plugin) {
        this.plugin = plugin;
        this.validator = new BattlegroundValidator();
        this.editor = new BattlegroundEditor();
        this.builder = new BattlegroundBuilder();
        this.enabledBattlegrounds = new HashMap<>();
        this.enabledMultigrounds = new HashMap<>();
    }

    public List<Battleground> getLoadedBattlegrounds() {
        return enabledBattlegrounds.values().stream().toList();
    }

    public List<Multiground> getMultigrounds() {
        return this.enabledMultigrounds.values().stream().toList();
    }

    @Nullable
    public Multiground getMultiground(String name) {
        return this.enabledMultigrounds.get(name);
    }

    public void reset(Battleground battleground) {
        disable(battleground);
        enable(battleground.getBattlegroundName());
    }

    public void launchMultiground(String name, List<String> battlegrounds) {
        Multiground multiground = new Multiground(name);
        battlegrounds.forEach(multiground::add);
        this.enabledMultigrounds.put(name, multiground);
        multiground.launchRandomly();
    }

    /* Returns true if not null */
    public boolean enable(String name) {
        return this.enable(name, null) != null;
    }

    public Battleground enable(String name, @Nullable Multiground multiground) {
        Battleground battleground = this.builder.build(name, multiground);

        // Если BattlegroundBuilder вернул null, то это значит лишь одно: арена запускается
        // вручную, а не через мультиграунд, при этом являясь его частью. (IS_MULTIGROUND)
        if (battleground == null) return null;

        battleground.setEnabled(true);
        battleground.setMultiground(multiground);

        this.enabledBattlegrounds.put(battleground.getBattlegroundName(), battleground);
        Bukkit.getServer().getPluginManager().registerEvents(battleground.getPreferences(), plugin);

        return battleground;
    }

    public void disable(Battleground battleground) {

        battleground.setEnabled(false);

        // Кикаем всех игроков с арены
        for (BattlegroundPlayer player : battleground.getPlayers()) {
            player.disconnect();
        }

        HandlerList.unregisterAll(battleground.getPreferences());
        this.enabledBattlegrounds.remove(battleground.getBattlegroundName());

        // Останавливаем все BukkitRunnable из правил автовыдачи
        for (BattlegroundTeam team : battleground.getTeams()) {

            if (team.getFlag() != null)
                team.getFlag().getTick().cancel();

            for (TeamRespawnPoint respawnPoint : team.getRespawnPoints()) {
                respawnPoint.getTick().cancel();
            }

            for (BattlegroundLoadout loadout : team.getLoadouts()) {
                for (BukkitRunnable task : loadout.getTasks()) {
                    task.cancel();
                }
            }
        }
    }

    @Nullable
    public Battleground getBattlegroundByName(String name) {
        return this.enabledBattlegrounds.get(name);
    }

}