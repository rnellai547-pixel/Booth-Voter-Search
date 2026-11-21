package com.boothvotersearch;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import android.text.TextWatcher;
import android.text.Editable;
import android.graphics.Typeface;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final int PICK_PDF = 41;
    private static final String PREFS = "booth_voter_local";
    private final ArrayList<JSONObject> voters = new ArrayList<>();
    private LinearLayout results;
    private EditText search;
    private TextView summary, status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());
        buildUi();
        loadSaved();
        render("");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(32, 36, 32, 24);
        page.setBackgroundColor(0xFFF5F7FA);
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("BOOTH VOTER SEARCH");
        title.setTextSize(23); title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF17365D);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("AVADI ASSEMBLY CONSTITUENCY  •  PART 194  •  ROLL 2027");
        subtitle.setTextSize(12); subtitle.setTextColor(0xFF536579);
        subtitle.setPadding(0, 8, 0, 22); page.addView(subtitle);

        summary = new TextView(this);
        summary.setTextSize(16); summary.setTypeface(null, Typeface.BOLD);
        summary.setTextColor(0xFF18324D); summary.setPadding(0,0,0,16);
        page.addView(summary);

        Button importButton = new Button(this);
        importButton.setText("IMPORT ELECTORAL ROLL PDF");
        importButton.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            startActivityForResult(i, PICK_PDF);
        });
        page.addView(importButton);

        status = new TextView(this);
        status.setText("Import the official Booth 194 PDF to begin.");
        status.setTextSize(13); status.setTextColor(0xFF536579);
        status.setPadding(0, 12, 0, 16); page.addView(status);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search name / EPIC / serial / house number");
        page.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { render(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, 18, 0, 0);
        page.addView(results);
        setContentView(scroll);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            status.setText("Reading PDF on this device…");
            new Thread(() -> {
                try {
                    String text;
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         PDDocument doc = PDDocument.load(in)) {
                        text = new PDFTextStripper().getText(doc);
                    }
                    ArrayList<JSONObject> parsed = parseRecords(text);
                    runOnUiThread(() -> {
                        voters.clear(); voters.addAll(parsed);
                        saveRecords();
                        status.setText("Imported " + voters.size() + " records. Check results against the original PDF.");
                        render(search.getText().toString());
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Could not read this PDF: " + e.getMessage()));
                }
            }).start();
        }
    }

    private ArrayList<JSONObject> parseRecords(String text) throws Exception {
        ArrayList<JSONObject> out = new ArrayList<>();
        // Each voter card begins with a serial number followed by a 10-character EPIC.
        Pattern start = Pattern.compile("(?m)(?:^|\\s)(\\d{1,3})\\s+([A-Z]{3}\\d{7})\\b");
        Matcher m = start.matcher(text);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<Integer> serials = new ArrayList<>();
        ArrayList<String> epics = new ArrayList<>();
        while (m.find()) {
            int serial = Integer.parseInt(m.group(1));
            if (serial >= 1 && serial <= 733) {
                starts.add(m.start(1)); serials.add(serial); epics.add(m.group(2));
            }
        }
        for (int i=0; i<starts.size(); i++) {
            int from = starts.get(i);
            int to = (i+1 < starts.size()) ? starts.get(i+1) : text.length();
            String chunk = text.substring(from, to);
            String name = field(chunk, "Name\\s*:");
            String relation = firstField(chunk, new String[]{"Father Name\\s*:", "Husband Name\\s*:", "Mother Name\\s*:", "Other\\s*:"});
            String house = field(chunk, "House Number\\s*:");
            Matcher ag = Pattern.compile("Age\\s*:\\s*(\\d+)\\s+Gender\\s*:\\s*(Male|Female|Third Gender)", Pattern.CASE_INSENSITIVE).matcher(chunk);
            String age="", gender="";
            if (ag.find()) { age=ag.group(1); gender=ag.group(2); }
            if (name.isEmpty()) continue;
            JSONObject obj = new JSONObject();
            obj.put("serial", serials.get(i)); obj.put("epic", epics.get(i));
            obj.put("name", clean(name)); obj.put("relation", clean(relation));
            obj.put("house", clean(house)); obj.put("age", age); obj.put("gender", gender);
            out.add(obj);
        }
        // De-duplicate serial+EPIC pairs that may be repeated in extraction order.
        LinkedHashMap<String,JSONObject> unique = new LinkedHashMap<>();
        for (JSONObject o: out) unique.put(o.optInt("serial")+"-"+o.optString("epic"), o);
        return new ArrayList<>(unique.values());
    }

    private String field(String s, String label) {
        Matcher m = Pattern.compile(label+"\\s*(.*?)(?=\\s+(?:Father Name|Husband Name|Mother Name|Other|House Number|Age)\\s*:|$)", Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(s);
        return m.find()?m.group(1).trim():"";
    }
    private String firstField(String s, String[] labels) {
        for (String l: labels) { String v=field(s,l); if(!v.isEmpty()) return v; }
        return "";
    }
    private String clean(String s) { return s.replaceAll("\\s+"," ").trim(); }

    private void saveRecords() {
        try {
            JSONArray a = new JSONArray();
            for (JSONObject o: voters) a.put(o);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("records", a.toString()).apply();
        } catch(Exception ignored) {}
    }
    private void loadSaved() {
        try {
            String raw=getSharedPreferences(PREFS, MODE_PRIVATE).getString("records","");
            if(!raw.isEmpty()) {
                JSONArray a=new JSONArray(raw);
                for(int i=0;i<a.length();i++) voters.add(a.getJSONObject(i));
                status.setText("Loaded " + voters.size() + " saved records from this phone.");
            }
        } catch(Exception ignored) {}
    }

    private void render(String query) {
        if (summary == null || results == null) return;
        summary.setText("Imported records: " + voters.size() + "  |  Official PDF total: 733");
        results.removeAllViews();
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        int shown=0;
        for (JSONObject o: voters) {
            String hay=(o.optString("name")+" "+o.optString("epic")+" "+o.optString("serial")+" "+o.optString("house")+" "+o.optString("relation")).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(18,14,18,14);
            card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
            cp.setMargins(0,0,0,12); card.setLayoutParams(cp);

            TextView n=new TextView(this);
            n.setText(o.optInt("serial")+".  "+o.optString("name"));
            n.setTextSize(17); n.setTypeface(null,Typeface.BOLD); n.setTextColor(0xFF17365D);
            card.addView(n);
            TextView details=new TextView(this);
            details.setText("EPIC: "+o.optString("epic")+"\nHouse: "+o.optString("house")+
                "\nAge: "+o.optString("age")+"   Gender: "+o.optString("gender")+
                (o.optString("relation").isEmpty()?"":"\nRelative: "+o.optString("relation")));
            details.setTextSize(13); details.setTextColor(0xFF35485C); details.setPadding(0,6,0,0);
            card.addView(details); results.addView(card); shown++;
            if(shown>=100) break;
        }
        TextView count=new TextView(this);
        count.setText("Showing "+shown+(shown==100?" results (limit 100). Refine your search.":" results."));
        count.setTextColor(0xFF536579); count.setPadding(0,8,0,18); results.addView(count);
    }
}
