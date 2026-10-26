package io.github.cryptid11.gps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wikipedia articles near a position, from Wikipedia's geosearch API. Blocking; call it off
 * the main thread. Wikipedia is blocked in mainland China, so this needs a VPN there.
 */
final class Wiki {
    static final class Article {
        final String title;
        final String url;
        final double metres;
        final double lat;
        final double lon;

        Article(String title, String url, double metres, double lat, double lon) {
            this.title = title;
            this.url = url;
            this.metres = metres;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** The API's largest search radius. */
    private static final int RADIUS_M = 10_000;
    private static final int LIMIT = 10;

    private Wiki() {}

    /**
     * Up to ten articles nearest to the position, closest first, from the Wikipedia in the
     * phone's language, or the English one when that has none nearby.
     */
    static List<Article> near(double lat, double lon, String agent) throws IOException {
        String language = Locale.getDefault().getLanguage();
        List<Article> found = language.isEmpty() || language.equals("en")
                ? new ArrayList<>() : search(language, lat, lon, agent);
        return found.isEmpty() ? search("en", lat, lon, agent) : found;
    }

    private static List<Article> search(String language, double lat, double lon, String agent) throws IOException {
        String host = "https://" + language + ".wikipedia.org";
        String query = String.format(Locale.US,
                "%s/w/api.php?action=query&list=geosearch&gscoord=%.6f%%7C%.6f&gsradius=%d&gslimit=%d"
                        + "&format=json&formatversion=2", host, lat, lon, RADIUS_M, LIMIT);
        HttpURLConnection c = (HttpURLConnection) new URL(query).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            // Wikimedia asks API clients to identify themselves.
            c.setRequestProperty("User-Agent", agent);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) body.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
            JSONArray hits = json.optJSONObject("query") == null ? new JSONArray()
                    : json.getJSONObject("query").optJSONArray("geosearch");
            List<Article> articles = new ArrayList<>();
            for (int i = 0; hits != null && i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                articles.add(new Article(hit.getString("title"),
                        host + "/?curid=" + hit.getLong("pageid"), hit.optDouble("dist", 0),
                        hit.getDouble("lat"), hit.getDouble("lon")));
            }
            return articles;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }
}
