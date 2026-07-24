package com.orffyrus.pest;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.instructions.Sensor;

public class IsLootingSensorBuilder extends BuilderSensorBase {

    @Override
    public String getShortDescription() {
        return "True while Pest is looting drops after combat/hunt.";
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
    public IsLootingSensorBuilder readConfig(JsonElement json) {
        return this;
    }

    @Override
    public Sensor build(BuilderSupport support) {
        return new IsLootingSensor(this, support);
    }
}
