package com.torrenta.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

import com.torrenta.webapi.Passwords;
import com.torrenta.webapi.WebApiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.torrenta.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Remote access page: the server's status as the service reports it, the offer of a free port
 * when the chosen one is taken, the password kept only as its hash, and typed fields applied once
 * the typing pauses. The service is a stand-in that records each apply.
 */
@ExtendWith(FxToolkit.class)
class SettingsRemoteFxTest {

    private record Remote(SettingsFixture f, List<String> applied) {}

    private static Remote open(Path dir, WebApiService.Status answer) {
        SettingsFixture f = SettingsFixture.open(dir);
        List<String> applied = new CopyOnWriteArrayList<>();
        Consumer<Consumer<WebApiService.Status>> apply = then -> {
            applied.add(
                    f.settings.webApi().address() + ":" + f.settings.webApi().port());
            then.accept(answer);
        };
        Fx.run(() -> f.window.setRemoteAccess(() -> answer, apply));
        f.page(SettingsWindow.Category.REMOTE);
        return new Remote(f, applied);
    }

    @Test
    void aTakenPortIsOfferedAFreeOneAndAnExposedAddressIsWarnedOf(@TempDir Path dir) {
        WebApiService.Status taken = new WebApiService.Status(WebApiService.State.FAILED, "Port 8080 is in use", 8091);
        Remote r = open(dir, taken);
        SettingsFixture f = r.f();
        assertTrue(f.says("Port 8080 is in use"));
        Button useFree = Fx.call(() -> Modals.first(
                        f.root(), Button.class, b -> b.getText().equals(tr("settings.remote.useFree", "8091")))
                .orElseThrow());
        Fx.run(useFree::fire);
        assertEquals(8091, f.settings.webApi().port());

        TextField address = f.control("settings.remote.address", TextField.class);
        Fx.run(() -> address.setText("0.0.0.0"));
        Fx.waitUntil(
                "the pause to apply the address", () -> r.applied().stream().anyMatch(a -> a.startsWith("0.0.0.0")));
        javafx.scene.control.CheckBox enabled =
                f.control("settings.remote.enabled", javafx.scene.control.CheckBox.class);
        Fx.run(() -> enabled.setSelected(true));
        assertTrue(f.settings.webApi().enabled());
        assertTrue(f.says(tr("settings.remote.exposed")), "a wildcard address over plain HTTP is warned of");
        Fx.run(() -> f.window.showRemoteStatus(WebApiService.Status.off()));
        assertFalse(f.says(tr("settings.remote.useFree", "8091")), "no offer without a taken port");
    }

    @Test
    void thePasswordIsHashedOffTheFxThreadAndNeverKept(@TempDir Path dir) {
        Remote r = open(dir, WebApiService.Status.off());
        SettingsFixture f = r.f();
        assertTrue(f.says(tr("settings.remote.password.notSet")));
        PasswordField password = f.control("settings.remote.password", PasswordField.class);
        Fx.run(() -> {
            password.setText("hunter2");
            password.fireEvent(new javafx.event.ActionEvent());
        });
        assertEquals("", Fx.call(password::getText), "the field empties at once");
        Fx.waitUntil("the hash", () -> Passwords.isSet(f.settings.webApi().passwordHash()));
        assertTrue(Passwords.verify("hunter2".toCharArray(), f.settings.webApi().passwordHash()));
        assertFalse(f.saved().containsValue("hunter2"));
        Fx.waitUntil("the line under it", () -> Modals.texts(f.root()).contains(tr("settings.remote.password.isSet")));
        // An empty field and Enter does nothing.
        Fx.run(() -> password.fireEvent(new javafx.event.ActionEvent()));
        assertFalse(r.applied().isEmpty(), "a new password is applied");
    }

    @Test
    void theHttpsFieldsAndTheRootsWriteThrough(@TempDir Path dir) {
        Remote r = open(dir, WebApiService.Status.off());
        SettingsFixture f = r.f();
        List<TextField> fields = f.fields();
        TextField https = fields.stream()
                .filter(t -> tr("settings.remote.httpsFile.prompt").equals(t.getPromptText()))
                .findFirst()
                .orElseThrow();
        Fx.run(() -> https.setText(dir.resolve("server.p12").toString()));
        PasswordField httpsPassword = f.control("settings.remote.httpsPassword", PasswordField.class);
        Fx.run(() -> httpsPassword.setText("changeit"));
        assertEquals(dir.resolve("server.p12"), f.settings.webApi().httpsFile());
        assertEquals("changeit", f.settings.webApi().httpsPassword());
        @SuppressWarnings("unchecked")
        Spinner<Integer> port = f.control("settings.remote.port", Spinner.class);
        Fx.run(() -> port.getValueFactory().setValue(8181));
        Fx.waitUntil("the pause to apply", () -> r.applied().stream().anyMatch(a -> a.endsWith(":8181")));
    }
}
