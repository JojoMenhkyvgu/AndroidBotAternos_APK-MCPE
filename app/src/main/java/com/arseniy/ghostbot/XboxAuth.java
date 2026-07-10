package com.arseniy.ghostbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

import okhttp3.*;

/**
 * Device code вход через Microsoft/Xbox Live - именно так логинится настоящий клиент Minecraft.
 * Пользователь открывает ссылку, вводит код под своим аккаунтом, приложение получает токены
 * и обменивает их на подписанную Mojang-цепочку сертификатов для LoginPacket.
 *
 * Как и с протокол-частью: константы (client_id, эндпоинты) взяты из открытых реализаций
 * (prismarine-auth/bedrock-protocol). Если Microsoft поменяет что-то на своей стороне -
 * поправим по логам ошибки.
 */
public class XboxAuth {

    private static final String TAG = "XboxAuth";

    // client_id "Minecraft for Nintendo Switch" - используется ботами, т.к. не требует
    // отдельной привязки покупки игры для базовой Xbox Live авторизации
    private static final String CLIENT_ID = "00000000441cc96b";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface CodeCallback {
        void onCode(String userCode, String verificationUri);
    }

    /**
     * Полный флоу. Блокирующий вызов - гонять только в фоновом потоке.
     * Возвращает identity chain (список JWT-строк) для LoginPacket.
     */
    public List<String> login(ECPublicKey publicKey, CodeCallback codeCallback, Consumer<String> status) throws Exception {
        Logger.i(TAG, "Запрашиваю device code у login.live.com");
        JsonNode deviceCodeResp = requestDeviceCode();
        Logger.d(TAG, "Ответ device code: " + deviceCodeResp);

        String deviceCode = deviceCodeResp.get("device_code").asText();
        String userCode = deviceCodeResp.get("user_code").asText();
        String verificationUri = deviceCodeResp.get("verification_uri").asText();
        int interval = deviceCodeResp.has("interval") ? deviceCodeResp.get("interval").asInt() : 5;
        int expiresIn = deviceCodeResp.has("expires_in") ? deviceCodeResp.get("expires_in").asInt() : 900;

        codeCallback.onCode(userCode, verificationUri);
        status.accept("Жду входа в аккаунт...");

        String msaAccessToken = pollForToken(deviceCode, interval, expiresIn);
        Logger.i(TAG, "MSA access token получен (длина " + msaAccessToken.length() + " символов)");
        status.accept("Вход выполнен, авторизация в Xbox Live...");

        JsonNode xblResp = xblAuthenticate(msaAccessToken);
        Logger.d(TAG, "XBL ответ получен");
        String xblToken = xblResp.get("Token").asText();

        JsonNode xstsResp = xstsAuthorize(xblToken);
        Logger.d(TAG, "XSTS ответ получен");
        String xstsToken = xstsResp.get("Token").asText();
        String uhs = xstsResp.get("DisplayClaims").get("xui").get(0).get("uhs").asText();
        Logger.i(TAG, "XSTS токен и uhs получены, uhs=" + uhs);

        status.accept("Получаю сертификат Minecraft...");
        List<String> chain = getMinecraftChain(publicKey, uhs, xstsToken);
        Logger.i(TAG, "Цепочка Minecraft получена, звеньев: " + chain.size());
        return chain;
    }

    private JsonNode requestDeviceCode() throws Exception {
        RequestBody body = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .add("response_type", "device_code")
                .build();
        Request request = new Request.Builder()
                .url("https://login.live.com/oauth20_connect.srf")
                .post(body)
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body().string();
            Logger.d(TAG, "device_code HTTP " + response.code() + ": " + raw);
            return mapper.readTree(raw);
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка запроса device code", e);
            throw e;
        }
    }

    private String pollForToken(String deviceCode, int intervalSeconds, int expiresIn) throws Exception {
        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            Thread.sleep(intervalSeconds * 1000L);
            Logger.d(TAG, "Проверка входа, попытка #" + attempt);

            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", CLIENT_ID)
                    .add("device_code", deviceCode)
                    .build();
            Request request = new Request.Builder()
                    .url("https://login.live.com/oauth20_token.srf")
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String raw = response.body().string();
                JsonNode json = mapper.readTree(raw);
                if (json.has("access_token")) {
                    Logger.i(TAG, "Токен получен на попытке #" + attempt);
                    return json.get("access_token").asText();
                }
                String error = json.has("error") ? json.get("error").asText() : "";
                if (!error.equals("authorization_pending")) {
                    Logger.e(TAG, "MSA login error: " + raw);
                    throw new RuntimeException("MSA login error: " + json);
                }
                Logger.d(TAG, "Ещё не вошёл, жду дальше (authorization_pending)");
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                Logger.e(TAG, "Ошибка при опросе токена", e);
                throw e;
            }
        }
        Logger.e(TAG, "Время на вход в аккаунт истекло");
        throw new RuntimeException("Время на вход в аккаунт истекло");
    }

    private JsonNode xblAuthenticate(String msaAccessToken) throws Exception {
        String bodyJson = "{"
                + "\"Properties\":{"
                + "\"AuthMethod\":\"RPS\","
                + "\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":\"d=" + msaAccessToken + "\""
                + "},"
                + "\"RelyingParty\":\"http://auth.xboxlive.com\","
                + "\"TokenType\":\"JWT\""
                + "}";
        Request request = new Request.Builder()
                .url("https://user.auth.xboxlive.com/user/authenticate")
                .addHeader("Accept", "application/json")
                .addHeader("x-xbl-contract-version", "1")
                .post(RequestBody.create(bodyJson, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body().string();
            Logger.d(TAG, "XBL auth HTTP " + response.code() + ": " + raw);
            if (!response.isSuccessful()) {
                throw new RuntimeException("XBL auth ошибка (" + response.code() + "): " + raw);
            }
            return mapper.readTree(raw);
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка XBL авторизации", e);
            throw e;
        }
    }

    private JsonNode xstsAuthorize(String xblToken) throws Exception {
        String bodyJson = "{"
                + "\"Properties\":{"
                + "\"SandboxId\":\"RETAIL\","
                + "\"UserTokens\":[\"" + xblToken + "\"]"
                + "},"
                + "\"RelyingParty\":\"https://multiplayer.minecraftservices.com/\","
                + "\"TokenType\":\"JWT\""
                + "}";
        Request request = new Request.Builder()
                .url("https://xsts.auth.xboxlive.com/xsts/authorize")
                .addHeader("Accept", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body().string();
            Logger.d(TAG, "XSTS HTTP " + response.code() + ": " + raw);
            JsonNode json = mapper.readTree(raw);
            if (!response.isSuccessful()) {
                // XErr коды типа 2148916233 (нет аккаунта Xbox) / 2148916238 (детский аккаунт без разрешения)
                // видно прямо в этом raw-ответе - см. лог
                throw new RuntimeException("XSTS ошибка (возможно нет лицензии/аккаунта Xbox): " + raw);
            }
            return json;
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка XSTS авторизации", e);
            throw e;
        }
    }

    private List<String> getMinecraftChain(ECPublicKey publicKey, String uhs, String xstsToken) throws Exception {
        String base64Key = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        String bodyJson = "{\"identityPublicKey\":\"" + base64Key + "\"}";

        Request request = new Request.Builder()
                .url("https://multiplayer.minecraftservices.com/authentication")
                .addHeader("Authorization", "XBL3.0 x=" + uhs + ";" + xstsToken)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(bodyJson, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body().string();
            Logger.d(TAG, "Minecraft chain HTTP " + response.code() + ": " + raw);
            if (!response.isSuccessful()) {
                throw new RuntimeException("Ошибка получения цепочки Minecraft: " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            List<String> chain = new ArrayList<>();
            for (JsonNode link : json.get("chain")) {
                chain.add(link.asText());
            }
            return chain;
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка получения цепочки Minecraft", e);
            throw e;
        }
    }
}
