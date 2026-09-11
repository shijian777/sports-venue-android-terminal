package com.codex.lockertest.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned cabinet preview without manufacturing a physical LockerTarget. */
public final class ControlPanelPreview {
    private final List<Integer> pages;
    private final List<AllOpenCommand> allOpenCommands;
    private final Map<Integer, List<Cabinet>> layers;

    public ControlPanelPreview(List<Integer> pages, List<AllOpenCommand> allOpenCommands,
            Map<Integer, List<Cabinet>> layers) {
        if (pages == null || pages.isEmpty() || allOpenCommands == null || layers == null) {
            throw new IllegalArgumentException("Preview data is invalid");
        }
        ArrayList<Integer> pageCopy = new ArrayList<>(pages.size());
        for (Integer page : pages) {
            if (page == null || page < 1 || page > 100 || pageCopy.contains(page)) {
                throw new IllegalArgumentException("Preview page is invalid");
            }
            pageCopy.add(page);
        }
        ArrayList<AllOpenCommand> commandCopy = new ArrayList<>(allOpenCommands.size());
        for (AllOpenCommand command : allOpenCommands) {
            if (command == null) throw new IllegalArgumentException("Preview command is invalid");
            commandCopy.add(command);
        }
        LinkedHashMap<Integer, List<Cabinet>> layerCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Cabinet>> entry : layers.entrySet()) {
            Integer layer = entry.getKey();
            List<Cabinet> cabinets = entry.getValue();
            if (layer == null || layer < 0 || layer > 100 || cabinets == null
                    || layerCopy.containsKey(layer)) {
                throw new IllegalArgumentException("Preview layer is invalid");
            }
            ArrayList<Cabinet> cabinetCopy = new ArrayList<>(cabinets.size());
            for (Cabinet cabinet : cabinets) {
                if (cabinet == null) throw new IllegalArgumentException("Preview cabinet is invalid");
                cabinetCopy.add(cabinet);
            }
            layerCopy.put(layer, Collections.unmodifiableList(cabinetCopy));
        }
        this.pages = Collections.unmodifiableList(pageCopy);
        this.allOpenCommands = Collections.unmodifiableList(commandCopy);
        this.layers = Collections.unmodifiableMap(layerCopy);
    }

    public List<Integer> pages() { return pages; }
    public List<AllOpenCommand> allOpenCommands() { return allOpenCommands; }
    public Map<Integer, List<Cabinet>> layers() { return layers; }

    public static final class AllOpenCommand {
        private final String boardHex;
        private final String command;

        public AllOpenCommand(String boardHex, String command) {
            this.boardHex = BusinessValues.text(boardHex, "Board hex", 64);
            this.command = BusinessValues.text(command, "All-open command", 512);
        }

        public String boardHex() { return boardHex; }
        public String command() { return command; }
    }

    public static final class Cabinet {
        private final long fcId;
        private final long channelId;
        private final String cabinetLabel;
        private final int status;
        private final long areaId;
        private final String boardHex;
        private final String channelNo;
        private final String openCommand;
        private final int checkStatus;

        public Cabinet(long fcId, long channelId, String cabinetLabel, int status,
                long areaId, String boardHex, String channelNo, String openCommand,
                int checkStatus) {
            this.fcId = BusinessValues.positive(fcId, "Cabinet id");
            this.channelId = BusinessValues.positive(channelId, "Channel id");
            this.cabinetLabel = BusinessValues.text(cabinetLabel, "Cabinet label", 256);
            this.status = BusinessValues.bounded(status, 0, 4, "Cabinet status");
            this.areaId = BusinessValues.positive(areaId, "Area id");
            this.boardHex = BusinessValues.text(boardHex, "Board hex", 64);
            this.channelNo = BusinessValues.text(channelNo, "Channel number", 64);
            this.openCommand = BusinessValues.text(openCommand, "Open command", 512);
            this.checkStatus = BusinessValues.bounded(checkStatus, 0, 4, "Check status");
        }

        public long fcId() { return fcId; }
        public long channelId() { return channelId; }
        public String cabinetLabel() { return cabinetLabel; }
        public int status() { return status; }
        public long areaId() { return areaId; }
        public String boardHex() { return boardHex; }
        public String channelNo() { return channelNo; }
        public String openCommand() { return openCommand; }
        public int checkStatus() { return checkStatus; }
    }
}
