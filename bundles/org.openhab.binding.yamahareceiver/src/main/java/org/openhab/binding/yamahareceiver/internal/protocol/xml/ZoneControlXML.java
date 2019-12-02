/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.yamahareceiver.internal.protocol.xml;

import static org.openhab.binding.yamahareceiver.internal.YamahaReceiverBindingConstants.*;
import static org.openhab.binding.yamahareceiver.internal.protocol.xml.XMLConstants.*;
import static org.openhab.binding.yamahareceiver.internal.protocol.xml.XMLConstants.Commands.*;
import static org.openhab.binding.yamahareceiver.internal.protocol.xml.XMLProtocolService.getZoneResponse;
import static org.openhab.binding.yamahareceiver.internal.protocol.xml.XMLUtils.*;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;

import org.apache.commons.lang.StringUtils;
import org.openhab.binding.yamahareceiver.internal.YamahaReceiverBindingConstants.Zone;
import org.openhab.binding.yamahareceiver.internal.config.YamahaZoneConfig;
import org.openhab.binding.yamahareceiver.internal.protocol.AbstractConnection;
import org.openhab.binding.yamahareceiver.internal.protocol.InputConverter;
import org.openhab.binding.yamahareceiver.internal.protocol.ReceivedMessageParseException;
import org.openhab.binding.yamahareceiver.internal.protocol.ZoneControl;
import org.openhab.binding.yamahareceiver.internal.state.DeviceInformationState;
import org.openhab.binding.yamahareceiver.internal.state.ZoneControlState;
import org.openhab.binding.yamahareceiver.internal.state.ZoneControlStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

/**
 * The zone protocol class is used to control one zone of a Yamaha receiver with HTTP/xml.
 * No state will be saved in here, but in {@link ZoneControlState} instead.
 *
 * @author David Gräff - Refactored
 * @author Eric Thill
 * @author Ben Jones
 * @author Tomasz Maruszak - Refactoring, input mapping fix, added Straight surround, volume DB fix and config
 *         improvement.
 */
public class ZoneControlXML implements ZoneControl {

    protected Logger logger = LoggerFactory.getLogger(ZoneControlXML.class);

    private static final String SURROUND_PROGRAM_STRAIGHT = "Straight";

    private final ZoneControlStateListener observer;
    private final Supplier<InputConverter> inputConverterSupplier;
    private final WeakReference<AbstractConnection> comReference;
    private final Zone zone;
    private final YamahaZoneConfig zoneConfig;
    private final DeviceDescriptorXML.ZoneDescriptor zoneDescriptor;

    protected CommandTemplate power = new CommandTemplate("<Power_Control><Power>%s</Power></Power_Control>",
            "Power_Control/Power");
    protected CommandTemplate mute = new CommandTemplate("<Volume><Mute>%s</Mute></Volume>", "Volume/Mute");
    protected CommandTemplate volume = new CommandTemplate(
            "<Volume><Lvl><Val>%d</Val><Exp>1</Exp><Unit>dB</Unit></Lvl></Volume>", "Volume/Lvl/Val");
    protected CommandTemplate inputSel = new CommandTemplate("<Input><Input_Sel>%s</Input_Sel></Input>",
            "Input/Input_Sel");
    protected String inputSelNamePath = "Input/Input_Sel_Item_Info/Title";
    protected CommandTemplate surroundSelProgram = new CommandTemplate(
            "<Surround><Program_Sel><Current><Sound_Program>%s</Sound_Program></Current></Program_Sel></Surround>",
            "Surround/Program_Sel/Current/Sound_Program");
    protected CommandTemplate surroundSelStraight = new CommandTemplate(
            "<Surround><Program_Sel><Current><Straight>On</Straight></Current></Program_Sel></Surround>",
            "Surround/Program_Sel/Current/Straight");
    protected CommandTemplate sceneSel = new CommandTemplate("<Scene><Scene_Sel>%s</Scene_Sel></Scene>");
    protected boolean sceneSelSupported = false;
    protected CommandTemplate dialogueLevel = new CommandTemplate(
            "<Sound_Video><Dialogue_Adjust><Dialogue_Lvl>%d</Dialogue_Lvl></Dialogue_Adjust></Sound_Video>",
            "Sound_Video/Dialogue_Adjust/Dialogue_Lvl");
    protected boolean dialogueLevelSupported = false;

    protected CommandTemplate speakerA = new CommandTemplate(
            "<Speaker_Preout><Speaker_AB><Speaker_A>%s</Speaker_A></Speaker_AB></Speaker_Preout>",
            "Speaker_Preout/Speaker_AB/Speaker_A");
    protected boolean speakerASupported = false;

    protected CommandTemplate speakerB = new CommandTemplate(
            "<Speaker_Preout><Speaker_AB><Speaker_B>%s</Speaker_B></Speaker_AB></Speaker_Preout>",
            "Speaker_Preout/Speaker_AB/Speaker_B");
    protected boolean speakerBSupported = false;

    protected CommandTemplate powerZoneB = new CommandTemplate(
            "<Power_Control><Zone_B_Power>%s</Zone_B_Power></Power_Control>", "Power_Control/Zone_B_Power_Info");
    protected boolean powerZoneBSupported = false;

    protected CommandTemplate muteZoneB = new CommandTemplate("<Volume><Zone_B><Mute>%s</Mute></Zone_B></Volume>",
            "Volume/Zone_B/Mute");
    protected boolean muteZoneBSupported = false;

    protected CommandTemplate volumeZoneB = new CommandTemplate(
            "<Volume><Zone_B><Lvl><Val>%d</Val><Exp>1</Exp><Unit>dB</Unit></Lvl></Zone_B></Volume>",
            "Volume/Zone_B/Lvl/Val");
    protected boolean volumeZoneBSupported = false;

    public ZoneControlXML(AbstractConnection con, Zone zone, YamahaZoneConfig zoneSettings,
            ZoneControlStateListener observer, DeviceInformationState deviceInformationState,
            Supplier<InputConverter> inputConverterSupplier) {

        this.comReference = new WeakReference<>(con);
        this.zone = zone;
        this.zoneConfig = zoneSettings;
        this.zoneDescriptor = DeviceDescriptorXML.getAttached(deviceInformationState).zones.getOrDefault(zone, null);
        this.observer = observer;
        this.inputConverterSupplier = inputConverterSupplier;

        this.applyModelVariations();

        try {

            Zone z = getZone();

            // read the basic status node containing the information needed to determine model variations
            Node basicStatusNode = getZoneResponse(comReference.get(), z, ZONE_BASIC_STATUS_CMD,
                    ZONE_BASIC_STATUS_PATH);

            // apply model variations
            this.applyModelVariationsSpeakerA(basicStatusNode);
            this.applyModelVariationsSpeakerB(basicStatusNode);
            this.applyModelVariationsPowerControlZoneBPowerInfo(basicStatusNode);
            this.applyModelVariationsVolumeZoneB(basicStatusNode);
            this.applyModelVariationsSurrPgmSelPgm(basicStatusNode);

        } catch (ReceivedMessageParseException | IOException e) {
            logger.warn("Could not perform feature detection for model variations.", e);
        }

    }

    /**
     * Applies model variations for speaker control for pair A.
     */
    protected void applyModelVariationsSpeakerA(Node basicStatusNode) {
        String speakerA = getNodeContentOrEmpty(basicStatusNode, "Speaker_Preout/Speaker_AB/Speaker_A");
        speakerASupported = StringUtils.isNotEmpty(speakerA);
        if (speakerASupported) {
            logger.debug("Zone {} - the {} channel is supported on your model", getZone(), CHANNEL_SPEAKER_A);
        } else {
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_SPEAKER_A);
        }
    }

    /**
     * Applies model variations for speaker control for pair B.
     */
    protected void applyModelVariationsSpeakerB(Node basicStatusNode) {
        String speakerB = getNodeContentOrEmpty(basicStatusNode, "Speaker_Preout/Speaker_AB/Speaker_B");
        speakerBSupported = StringUtils.isNotEmpty(speakerB);
        if (speakerBSupported) {
            logger.debug("Zone {} - the {} channel is supported on your model", getZone(), CHANNEL_SPEAKER_B);
        } else {
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_SPEAKER_B);
        }

    }

    /**
     * Applies model variations for power control for Zone_B.
     */
    protected void applyModelVariationsPowerControlZoneBPowerInfo(Node basicStatusNode) {
        String powerControlZoneBPower = getNodeContentOrEmpty(basicStatusNode, "Power_Control/Zone_B_Power_Info");
        powerZoneBSupported = StringUtils.isNotEmpty(powerControlZoneBPower);
        if (powerZoneBSupported) {
            logger.debug("Zone {} - the {} channel is supported on your model", getZone(), CHANNEL_ZONE_B_POWER);
        } else {
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_ZONE_B_POWER);
        }
    }

    /**
     * Applies model variations for volume control for Zone_B.
     */
    protected void applyModelVariationsVolumeZoneB(Node basicStatusNode) {
        String volumeZoneB = getNodeContentOrEmpty(basicStatusNode, "Volume/Zone_B");
        muteZoneBSupported = volumeZoneBSupported = StringUtils.isNotEmpty(volumeZoneB);
        if (muteZoneBSupported) {
            logger.debug("Zone {} - the {} channel is supported on your model", getZone(), CHANNEL_ZONE_B_MUTE);
            logger.debug("Zone {} - the {} channel is supported on your model", getZone(), CHANNEL_ZONE_B_VOLUME);
        } else {
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_ZONE_B_MUTE);
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_ZONE_B_VOLUME);
        }
    }

    /**
     * Applies model variations for surround program.
     *
     * @param basicStatusNode
     */
    protected void applyModelVariationsSurrPgmSelPgm(Node basicStatusNode) {
        String surroundProgram = getNodeContentOrEmpty(basicStatusNode, "Surr/Pgm_Sel/Pgm");

        if (StringUtils.isNotEmpty(surroundProgram)) {
            surroundSelProgram = new CommandTemplate(
                    "<Surr><Pgm_Sel><Straight>Off</Straight><Pgm>%s</Pgm></Pgm_Sel></Surr>", "Surr/Pgm_Sel/Pgm");
            logger.debug("Zone {} - adjusting command to: {}", getZone(), surroundSelProgram);

            surroundSelStraight = new CommandTemplate("<Surr><Pgm_Sel><Straight>On</Straight></Pgm_Sel></Surr>",
                    "Surr/Pgm_Sel/Straight");
            logger.debug("Zone {} - adjusting command to: {}", getZone(), surroundSelStraight);
        }
    }

    /**
     * Apply command changes to ensure compatibility with all supported models
     */
    protected void applyModelVariations() {
        if (zoneDescriptor == null) {
            logger.trace("Zone {} - descriptor not available", getZone());
            return;
        }

        logger.trace("Zone {} - compatibility detection", getZone());

        // Note: Detection if scene is supported
        sceneSelSupported = zoneDescriptor.hasCommandEnding("Scene,Scene_Sel", () -> logger
                .debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_SCENE));

        // Note: Detection if dialogue level is supported
        dialogueLevelSupported = zoneDescriptor.hasAnyCommandEnding("Sound_Video,Dialogue_Adjust,Dialogue_Lvl",
                "Sound_Video,Dialogue_Adjust,Dialogue_Lift");
        if (zoneDescriptor.hasCommandEnding("Sound_Video,Dialogue_Adjust,Dialogue_Lift")) {
            dialogueLevel = dialogueLevel.replace("Dialogue_Lvl", "Dialogue_Lift");
            logger.debug("Zone {} - adjusting command to: {}", getZone(), dialogueLevel);
        }
        if (!dialogueLevelSupported) {
            logger.debug("Zone {} - the {} channel is not supported on your model", getZone(), CHANNEL_DIALOGUE_LEVEL);
        }

        // Note: Detection for RX-V3900, which uses <Vol> instead of <Volume>
        if (zoneDescriptor.hasCommandEnding("Vol,Lvl")) {
            volume = volume.replace("Volume", "Vol");
            logger.debug("Zone {} - adjusting command to: {}", getZone(), volume);
        }
        if (zoneDescriptor.hasCommandEnding("Vol,Mute")) {
            mute = mute.replace("Volume", "Vol");
            logger.debug("Zone {} - adjusting command to: {}", getZone(), mute);
        }
    }

    protected void sendCommand(String message) throws IOException {
        comReference.get().send(XMLUtils.wrZone(zone, message));
    }

    /**
     * Return the zone
     */
    public Zone getZone() {
        return zone;
    }

    @Override
    public void setPower(boolean on) throws IOException, ReceivedMessageParseException {
        String cmd = power.apply(on ? ON : POWER_STANDBY);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setMute(boolean on) throws IOException, ReceivedMessageParseException {
        String cmd = this.mute.apply(on ? ON : OFF);
        sendCommand(cmd);
        update();
    }

    /**
     * Sets the absolute volume in decibel.
     *
     * @param volume Absolute value in decibel ([-80,+12]).
     * @throws IOException
     */
    @Override
    public void setVolumeDB(float volume) throws IOException, ReceivedMessageParseException {
        if (volume < zoneConfig.getVolumeDbMin()) {
            volume = zoneConfig.getVolumeDbMin();
        }
        if (volume > zoneConfig.getVolumeDbMax()) {
            volume = zoneConfig.getVolumeDbMax();
        }

        // Yamaha accepts only integer values with .0 or .5 at the end only (-20.5dB, -20.0dB) - at least on RX-S601D.
        // The order matters here. We want to cast to integer first and then scale by 10.
        // Effectively we're only allowing dB values with .0 at the end.
        int vol = (int) volume * 10;
        sendCommand(this.volume.apply(vol));
        update();
    }

    /**
     * Sets the volume in percent
     *
     * @param volume
     * @throws IOException
     */
    @Override
    public void setVolume(float volume) throws IOException, ReceivedMessageParseException {
        if (volume < 0) {
            volume = 0;
        }
        if (volume > 100) {
            volume = 100;
        }
        // Compute value in db
        setVolumeDB(zoneConfig.getVolumeDb(volume));
    }

    /**
     * Increase or decrease the volume by the given percentage.
     *
     * @param percent
     * @throws IOException
     */
    @Override
    public void setVolumeRelative(ZoneControlState state, float percent)
            throws IOException, ReceivedMessageParseException {
        setVolume(zoneConfig.getVolumePercentage(state.volumeDB) + percent);
    }

    @Override
    public void setInput(String name) throws IOException, ReceivedMessageParseException {
        name = inputConverterSupplier.get().toCommandName(name);
        String cmd = inputSel.apply(name);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setSurroundProgram(String name) throws IOException, ReceivedMessageParseException {
        String cmd = name.equalsIgnoreCase(SURROUND_PROGRAM_STRAIGHT) ? surroundSelStraight.apply()
                : surroundSelProgram.apply(name);

        sendCommand(cmd);
        update();
    }

    @Override
    public void setDialogueLevel(int level) throws IOException, ReceivedMessageParseException {
        if (!dialogueLevelSupported) {
            return;
        }
        sendCommand(dialogueLevel.apply(level));
        update();
    }

    @Override
    public void setScene(String scene) throws IOException, ReceivedMessageParseException {
        if (!sceneSelSupported) {
            return;
        }
        sendCommand(sceneSel.apply(scene));
        update();
    }

    @Override
    public void update() throws IOException, ReceivedMessageParseException {
        if (observer == null) {
            return;
        }

        Node statusNode = getZoneResponse(comReference.get(), zone, ZONE_BASIC_STATUS_CMD, ZONE_BASIC_STATUS_PATH);

        String value;

        ZoneControlState state = new ZoneControlState();

        value = getNodeContentOrEmpty(statusNode, power.getPath());
        state.power = ON.equalsIgnoreCase(value);

        value = getNodeContentOrEmpty(statusNode, mute.getPath());
        state.mute = ON.equalsIgnoreCase(value);

        // The value comes in dB x 10, on AVR it says -30.5dB, the values comes as -305
        value = getNodeContentOrDefault(statusNode, volume.getPath(), String.valueOf(zoneConfig.getVolumeDbMin()));
        state.volumeDB = Float.parseFloat(value) * .1f; // in dB

        value = getNodeContentOrEmpty(statusNode, inputSel.getPath());
        state.inputID = inputConverterSupplier.get().fromStateName(value);
        if (StringUtils.isBlank(state.inputID)) {
            throw new ReceivedMessageParseException("Expected inputID. Failed to read Input/Input_Sel");
        }

        // Some receivers may use Src_Name instead?
        value = getNodeContentOrEmpty(statusNode, inputSelNamePath);
        state.inputName = value;

        value = getNodeContentOrEmpty(statusNode, surroundSelStraight.getPath());
        boolean straightOn = ON.equalsIgnoreCase(value);

        value = getNodeContentOrEmpty(statusNode, surroundSelProgram.getPath());
        // Surround is either in straight mode or sound program
        state.surroundProgram = straightOn ? SURROUND_PROGRAM_STRAIGHT : value;

        value = getNodeContentOrDefault(statusNode, dialogueLevel.getPath(), "0");
        state.dialogueLevel = Integer.parseInt(value);

        logger.debug("Zone {} state - power: {}, mute: {}, volumeDB: {}, input: {}, surroundProgram: {}", getZone(),
                state.power, state.mute, state.volumeDB, state.inputID, state.surroundProgram);

        observer.zoneStateChanged(state);
    }

    @Override
    public void setSpeakerA(boolean on) throws IOException, ReceivedMessageParseException {
        if (!speakerASupported) {
            return;
        }
        String cmd = speakerA.apply(on ? ON : OFF);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setSpeakerB(boolean on) throws IOException, ReceivedMessageParseException {
        if (!speakerBSupported) {
            return;
        }
        String cmd = speakerB.apply(on ? ON : OFF);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setZoneBPower(boolean on) throws IOException, ReceivedMessageParseException {
        if (!powerZoneBSupported) {
            return;
        }
        String cmd = powerZoneB.apply(on ? ON : POWER_STANDBY);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setZoneBMute(boolean on) throws IOException, ReceivedMessageParseException {
        if (!muteZoneBSupported) {
            return;
        }
        String cmd = muteZoneB.apply(on ? ON : OFF);
        sendCommand(cmd);
        update();
    }

    @Override
    public void setZoneBVolume(float volume) throws IOException, ReceivedMessageParseException {
        if (!volumeZoneBSupported) {
            return;
        }

        if (volume < zoneConfig.getVolumeDbMin()) {
            volume = zoneConfig.getVolumeDbMin();
        }
        if (volume > zoneConfig.getVolumeDbMax()) {
            volume = zoneConfig.getVolumeDbMax();
        }

        // Yamaha accepts only integer values with .0 or .5 at the end only (-20.5dB, -20.0dB) - at least on RX-S601D.
        // The order matters here. We want to cast to integer first and then scale by 10.
        // Effectively we're only allowing dB values with .0 at the end.
        int vol = (int) volume * 10;
        sendCommand(this.volumeZoneB.apply(vol));
        update();
    }
}
