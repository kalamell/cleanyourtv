package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The typeface behind one of the font_* strings in res/values/fonts.xml, for views built in code. The string
 * points at a bundled font, or, in locales Outfit can't draw (values-vi), names a system family (#833).
 *
 * The layouts are drawn in Kanit (fonts.xml). Launcher settings can pick another bundled Thai family
 * (KEY); apply() then swaps each Kanit weight on screen for the same weight of the chosen family.
 */
final class UiFont {
    private UiFont() {}

    static final String KEY = "ui_font";
    static final String[] FAMILIES = {"kanit", "prompt", "sarabun", "mitr"};
    static final String[] LABELS = {"Kanit", "Prompt", "Sarabun", "Mitr"};
    /** Per family: extra light, light, regular, medium, the four weights fonts.xml names. */
    private static final int[][] WEIGHTS = {
            {R.font.kanit_extralight, R.font.kanit_light, R.font.kanit_regular, R.font.kanit_medium},
            {R.font.prompt_extralight, R.font.prompt_light, R.font.prompt_regular, R.font.prompt_medium},
            {R.font.sarabun_extralight, R.font.sarabun_light, R.font.sarabun_regular, R.font.sarabun_medium},
            {R.font.mitr_extralight, R.font.mitr_light, R.font.mitr_regular, R.font.mitr_medium},
    };

    static Typeface get(Context context, int fontString) {
        Resources res = context.getResources();
        TypedValue value = new TypedValue();
        res.getValue(fontString, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return map(context, res.getFont(value.resourceId));
        return Typeface.create(name, Typeface.NORMAL);
    }

    /** The chosen family's index in FAMILIES; 0 (Kanit, the layouts' own) when unset or unknown. */
    static int chosen(Context context) {
        String name = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(KEY, null);
        for (int i = 0; i < FAMILIES.length; i++) if (FAMILIES[i].equals(name)) return i;
        return 0;
    }

    /** Kanit weight to the chosen family's same weight; empty when Kanit is chosen. */
    private static Map<Typeface, Typeface> mapping(Context context) {
        Map<Typeface, Typeface> map = new IdentityHashMap<>();
        int family = chosen(context);
        if (family == 0) return map;
        Resources res = context.getResources();
        for (int w = 0; w < 4; w++) map.put(res.getFont(WEIGHTS[0][w]), res.getFont(WEIGHTS[family][w]));
        return map;
    }

    private static Typeface map(Context context, Typeface typeface) {
        Typeface mapped = mapping(context).get(typeface);
        return mapped != null ? mapped : typeface;
    }

    /** Swap the fonts under root, and in rows a RecyclerView attaches later, for the chosen family. */
    static void apply(Context context, View root) {
        Map<Typeface, Typeface> map = mapping(context);
        if (!map.isEmpty()) walk(root, map);
    }

    private static void walk(View view, Map<Typeface, Typeface> map) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface mapped = map.get(text.getTypeface());
            if (mapped != null) text.setTypeface(mapped);
        }
        if (view instanceof RecyclerView) {
            ((RecyclerView) view).addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View child) {
                    walk(child, map);
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View child) {
                }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), map);
        }
    }
}
