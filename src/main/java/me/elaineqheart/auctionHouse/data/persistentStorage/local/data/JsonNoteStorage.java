package me.elaineqheart.auctionHouse.data.persistentStorage.local.data;

import com.google.gson.Gson;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class JsonNoteStorage {

    private static Gson gson;

    public static void createNote(ItemNote note){
        AuctionHouseStorage.add(note);

        try {
            saveNotesIfJson();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteNote(ItemNote note) {
        AuctionHouseStorage.remove(note);

        try {
            saveNotesIfJson();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Persist the in-memory list to disk only when the JSON backend is in use.
     * When MySQL persistence is selected, this is a no-op.
     */
    public static void saveNotesIfJson() throws IOException {
        if (me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager.isMysqlPersistence()) {
            return;
        }
        saveNotes();
    }

    public static void saveNotes() throws IOException {
        File file = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + "/data/notes.json");
        file.getParentFile().mkdir();
        file.createNewFile();
        Writer writer = new FileWriter(file, false);
        getGson().toJson(AuctionHouseStorage.getAll(), writer);
        writer.flush();
        writer.close();
    }

    public static void loadNotes() throws IOException {
        if (ConfigManager.backwardsCompatibility()) backwardsCompatibility();
        File file = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + "/data/notes.json");
        if(file.exists()){
            Reader reader = new FileReader(file);
            ItemNote[] items = getGson().fromJson(reader, ItemNote[].class);
            AuctionHouseStorage.set(items);
        }
    }

    public static void backwardsCompatibility() throws IOException {
        File file = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + "/data/notes.json");
        File old = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + "/notes.json");
        if (old.exists()) {
            file.getParentFile().mkdir();
            file.createNewFile();
            Files.copy(old.getAbsoluteFile().toPath(), file.getAbsoluteFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            old.delete();
        }
    }

    public static void purge() {
        AuctionHouseStorage.set(new ItemNote[0]);
        try {
            saveNotesIfJson();
            loadNotes();
            // ItemNoteStorage.reload();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Gson getGson() {
        if(gson == null) gson = new Gson();
        return gson;
    }
}
