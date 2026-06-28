package com.dungeon.master.model.enums;

/** Who a spell may target — used to validate the player's chosen combat targets. */
public enum SpellTargetType {
    ENEMY,
    ALLY,
    SELF,
    AREA,
    ANY
}
