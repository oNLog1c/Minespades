package me.nologic.minespades.battleground;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import me.nologic.minespades.battleground.editor.loadout.BattlegroundLoadout;
import org.bukkit.entity.Player;

@RequiredArgsConstructor
public class BattlegroundPlayer {

    private @Getter final Battleground     battleground;
    private @Getter final BattlegroundTeam team;
    private @Getter final Player           player;

    private @Getter BattlegroundLoadout loadout;

    private @Setter @Getter int kills, deaths, assists;

    public void setRandomLoadout() {
        loadout = team.getLoadouts().get((int) (Math.random() * team.getLoadouts().size()));
        player.getInventory().setContents(loadout.getInventory().getContents());
    }

}