package com.game.client.entity;

import com.game.client.dungeon.Facing;

public class CharacterEntity {

    public final String id;
    public final CharacterClass charClass;

    public int        gridX;
    public int        gridZ;
    public Facing     facing;
    public CharacterState state;
    public WeaponType weaponType;

    public CharacterEntity(String id, CharacterClass charClass,
                           int gridX, int gridZ, Facing facing) {
        this.id         = id;
        this.charClass  = charClass;
        this.gridX      = gridX;
        this.gridZ      = gridZ;
        this.facing     = facing;
        this.state      = CharacterState.IDLE;
        this.weaponType = WeaponType.UNARMED;
    }
}
