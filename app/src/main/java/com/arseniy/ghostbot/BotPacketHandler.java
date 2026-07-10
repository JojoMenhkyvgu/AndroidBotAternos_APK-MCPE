package com.arseniy.ghostbot;

import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Обработчик пакетов "призрачного" клиента.
 * Логика: подключиться -> войти через настоящий Xbox Live (device code, см. XboxAuth)
 * -> дождаться StartGamePacket -> подтвердить инициализацию -> уйти в спектатор командой.
 *
 * Код для входа и ссылку смотри в уведомлении приложения (статус сервиса), либо в логах
 * (кнопка "Логи" в приложении).
 *
 * ВАЖНО: это первая сборка. Названия части пакетов/методов в CloudburstMC Protocol
 * периодически меняются между версиями библиотеки - если сборка в GitHub Actions
 * упадёт на конкретных импортах, кидай мне лог, поправим по месту (как с прошлыми проектами).
 */
public class BotPacketHandler implements BedrockPacketHandler {

    private static final String TAG = "BotPacketHandler";

    private final String displayName;
    private final Consumer<String> statusCallback;
    private BedrockClientSession session;
    private KeyPair keyPair;

    static {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    public BotPacketHandler(String displayName, Consumer<String> statusCallback) {
        this.displayName = displayName;
        this.statusCallback = statusCallback;
    }

    public void attachSession(BedrockClientSession session) {
        this.session = session;
        Logger.i(TAG, "Сессия привязана, генерирую ключи и начинаю логин");
        try {
            keyPair = generateKeyPair();
            Logger.d(TAG, "Ключевая пара сгенерирована (secp384r1)");
            sendLoginWithXboxAuth();
        } catch (Exception e) {
            Logger.e(TAG, "Ошибка авторизации", e);
            statusCallback.accept("Ошибка авторизации: " + e.getMessage());
        }
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "BC");
        gen.initialize(new org.bouncycastle.jce.spec.ECGenParameterSpec("secp384r1"));
        return gen.generateKeyPair();
    }

    private void sendLoginWithXboxAuth() throws Exception {
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();

        Logger.i(TAG, "Запускаю Xbox Live device code flow...");
        XboxAuth auth = new XboxAuth();
        java.util.List<String> chain = auth.login(
                publicKey,
                (userCode, verificationUri) -> {
                    Logger.i(TAG, "КОД ДЛЯ ВХОДА: " + userCode + " | ссылка: " + verificationUri);
                    statusCallback.accept("Открой " + verificationUri + " и введи код: " + userCode);
                },
                status -> {
                    Logger.i(TAG, "Статус авторизации: " + status);
                    statusCallback.accept(status);
                }
        );
        Logger.i(TAG, "Получена цепочка сертификатов Minecraft, звеньев: " + chain.size());

        String base64PublicKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());

        String clientDataJwt = Jwts.builder()
                .header().add("x5u", base64PublicKey).and()
                .claim("ClientRandomId", System.currentTimeMillis())
                .claim("CurrentInputMode", 1)
                .claim("DefaultInputMode", 1)
                .claim("DeviceModel", "Android Device")
                .claim("DeviceOS", 1)
                .claim("DeviceId", UUID.randomUUID().toString())
                .claim("GameVersion", "1.21.0")
                .claim("GuiScale", 0)
                .claim("LanguageCode", "ru_RU")
                .claim("ServerAddress", "")
                .claim("ThirdPartyName", displayName)
                .claim("UIProfile", 0)
                .signWith(privateKey, SignatureAlgorithm.ES384)
                .compact();
        Logger.d(TAG, "clientData JWT собран и подписан");

        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(766); // TODO: сверить с версией сервера
        login.getChain().addAll(chain);
        login.setExtra(clientDataJwt);

        Logger.i(TAG, "Отправляю LoginPacket (protocolVersion=766)");
        session.sendPacketImmediately(login);
        statusCallback.accept("Вход отправлен...");
    }

    @Override
    public boolean handle(NetworkSettingsPacket packet) {
        Logger.i(TAG, "NetworkSettingsPacket получен, включаю ZLIB-компрессию");
        session.setCompression(PacketCompressionAlgorithm.ZLIB);
        return true;
    }

    @Override
    public boolean handle(PlayStatusPacket packet) {
        Logger.i(TAG, "PlayStatusPacket: " + packet.getStatus());
        statusCallback.accept("PlayStatus: " + packet.getStatus());
        return true;
    }

    @Override
    public boolean handle(ResourcePacksInfoPacket packet) {
        Logger.i(TAG, "ResourcePacksInfoPacket получен, отвечаю HAVE_ALL_PACKS");
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        session.sendPacket(response);
        return true;
    }

    @Override
    public boolean handle(ResourcePackStackPacket packet) {
        Logger.i(TAG, "ResourcePackStackPacket получен, отвечаю COMPLETED");
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        session.sendPacket(response);
        return true;
    }

    @Override
    public boolean handle(StartGamePacket packet) {
        Logger.i(TAG, "StartGamePacket получен, runtimeEntityId=" + packet.getRuntimeEntityId());
        statusCallback.accept("В игре, ухожу в спектатор...");

        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(packet.getRuntimeEntityId());
        session.sendPacket(initialized);
        Logger.d(TAG, "SetLocalPlayerAsInitializedPacket отправлен");

        // Через команду, т.к. права оператора на сервере уже есть
        CommandRequestPacket cmd = new CommandRequestPacket();
        cmd.setCommand("/gamemode spectator " + displayName);
        cmd.setCommandOriginData(new org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData(
                org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType.PLAYER,
                UUID.randomUUID(), "", 0));
        cmd.setInternal(false);
        session.sendPacket(cmd);
        Logger.i(TAG, "Команда /gamemode spectator отправлена");

        statusCallback.accept("Подключено, режим спектатора активирован");
        return true;
    }

    @Override
    public boolean handle(DisconnectPacket packet) {
        Logger.e(TAG, "DisconnectPacket: " + packet.getKickMessage());
        statusCallback.accept("Отключено сервером: " + packet.getKickMessage());
        return true;
    }
}
