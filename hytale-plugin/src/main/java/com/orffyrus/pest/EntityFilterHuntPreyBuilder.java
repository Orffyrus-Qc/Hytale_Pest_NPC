package com.orffyrus.pest;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderEntityFilterBase;

public class EntityFilterHuntPreyBuilder extends BuilderEntityFilterBase {

    @Override
    public String getShortDescription() {
        return "Hunt prey only: wildlife/hostiles; blocks merchants and friendlies.";
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
    public EntityFilterHuntPreyBuilder readConfig(JsonElement json) {
        return this;
    }

    @Override
    public IEntityFilter build(BuilderSupport support) {
        return new EntityFilterHuntPrey(this, support);
    }
}
