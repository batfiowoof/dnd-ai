package com.dungeon.master.model.enums;

/**
 * Host-chosen session difficulty. Scales combat encounters (enemy count / HP / attack
 * bonus) and the ability-check DC band the DM is told to use.
 */
public enum Difficulty {
    EASY,
    NORMAL,
    DEADLY
}
