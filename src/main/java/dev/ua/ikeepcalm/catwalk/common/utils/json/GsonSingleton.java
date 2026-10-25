package dev.ua.ikeepcalm.catwalk.common.utils.json;

import com.google.gson.Gson;

public class GsonSingleton {

    private static final Gson INSTANCE = new Gson();

    public static Gson getInstance() {
        return INSTANCE;
    }
}
