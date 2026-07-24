package com.orffyrus.pest;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

public class NoteNearbyPreyActionBuilder extends BuilderActionBase {

    @Override
    public String getShortDescription() {
        return "Notes a nearby wildlife/NPC as prey for resource hunting.";
    }

    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Experimental;
    }

    @Override
    public NoteNearbyPreyActionBuilder readConfig(JsonElement json) {
        return this;
    }

    @Override
    public Action build(BuilderSupport support) {
        return new NoteNearbyPreyAction(this, support);
    }
}
