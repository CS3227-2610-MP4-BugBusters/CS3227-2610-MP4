package mp4.auth;

import static org.junit.jupiter.api.Assertions.*;
import static mp4.auth.SessionAuthenticationTest.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.net.http.HttpResponse;

class AccountManagementTest {
    @BeforeAll static void start() throws Exception { setup(); }
    @AfterAll static void stop() { shutdown(); }
    static HttpResponse<String> post(Browser b, String path, String body) throws Exception {
        return b.request("POST", path, body, "Content-Type", "application/json", "X-CSRF-TOKEN", b.csrf());
    }
    static Account demo() { return new Account(UUID.fromString("00000000-0000-4000-8000-000000000001"), "demo.maintainer", "demo-maintainer-2026", "MAINTAINER"); }

    @Test void startupSeedIsStableAndCannotBeChangedThroughWebOrOperator() throws Exception {
        Browser b = authenticated(demo());
        status(200, b.get("/api/account"), "seed account information");
        assertEquals(true, json(b.get("/api/account")).get("demoLocked"));
        status(403, post(b, "/api/account/password", "{\"currentPassword\":\"demo-maintainer-2026\",\"newPassword\":\"replacement-password\"}"), "seed password is locked");
        try (var c=connection(); var s=c.prepareStatement("UPDATE app_accounts SET display_name='Changed' WHERE username='demo.maintainer'")) {
            assertThrows(java.sql.SQLException.class, s::executeUpdate);
        }
        assertThrows(java.sql.SQLException.class, () -> new AccountProvisioningService(db).change("reset-password", "demo.maintainer", "replacement-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (Server replica = new Server()) {
            Browser again = new Browser(replica);
            status(200, again.login(demo(), demo().password(), again.csrf()), "seed survives restart");
        }
    }

    @Test void maintainerCreatesEveryRoleAndUsersChangeOnlyTheirOwnPassword() throws Exception {
        Browser owner = authenticated(account("MAINTAINER"));
        for (String role : List.of("REPORTER", "DEVELOPER", "MAINTAINER")) {
            String username="web."+UUID.randomUUID().toString().substring(0,12);
            String body="{\"username\":\""+username+"\",\"displayName\":\"Web account\",\"role\":\""+role+"\",\"password\":\"initial-password\"}";
            var created=post(owner,"/api/maintainer/accounts",body);
            status(201,created,"Maintainer creates "+role);
            assertFalse(created.body().contains("password"));
            status(409,post(owner,"/api/maintainer/accounts",body),"duplicate rejected");
            Account user=new Account(UUID.fromString((String)json(created).get("id")),username,"initial-password",role);
            Browser first=authenticated(user), second=authenticated(user);
            status(400,post(first,"/api/account/password","{\"currentPassword\":\"wrong-password\",\"newPassword\":\"changed-password\"}"),"current password required");
            status(204,post(first,"/api/account/password","{\"currentPassword\":\"initial-password\",\"newPassword\":\"changed-password\"}"),"own password changed");
            status(401,first.get("/api/auth/me"),"current session revoked");
            status(401,second.get("/api/auth/me"),"other session revoked");
            Browser login=new Browser(app);
            status(401,login.login(user,user.password(),login.csrf()),"old password rejected");
            status(200,login.login(user,"changed-password",login.csrf()),"new password works");
        }
    }

    @Test void creationRequiresMaintainerAndCsrfAndRejectsIdentityOverrides() throws Exception {
        String body="{\"username\":\"not.created\",\"displayName\":\"Denied\",\"role\":\"MAINTAINER\",\"password\":\"initial-password\"}";
        status(401,post(new Browser(app),"/api/maintainer/accounts",body),"anonymous denied");
        for(String role:List.of("REPORTER","DEVELOPER")) {
            Browser b=authenticated(account(role));
            status(403,post(b,"/api/maintainer/accounts",body),"wrong role denied");
            status(400,post(b,"/api/account/password","{\"currentPassword\":\"initial-password\",\"newPassword\":\"changed-password\",\"username\":\"demo.maintainer\"}"),"cannot target another account");
        }
        Browser owner=authenticated(account("MAINTAINER"));
        status(403,owner.request("POST","/api/maintainer/accounts",body,"Content-Type","application/json"),"CSRF required");
    }

    @Test void activeAccountCanChangeOnlyItsOwnDisplayNameAndIdentityReadsTheNewName() throws Exception {
        Account reporter=account("REPORTER"), other=account("DEVELOPER");
        Browser browser=authenticated(reporter);
        String twoHundredUtf16Units="🙂".repeat(100);

        status(204,post(browser,"/api/account/display-name","{\"displayName\":\""+twoHundredUtf16Units+"\"}"),"200 UTF-16 units accepted");
        for(String path:List.of("/api/account","/api/auth/me")) {
            var response=browser.get(path);
            status(200,response,"updated identity from "+path);
            var data=json(response);
            assertEquals(reporter.id().toString(),data.get("id"));
            assertEquals(twoHundredUtf16Units,data.get("displayName"));
            assertEquals("REPORTER",data.get("role"));
            if(path.equals("/api/auth/me")) assertEquals(true,data.get("active"));
        }

        for(String invalid:List.of("", " ", "x".repeat(201)))
            status(400,post(browser,"/api/account/display-name","{\"displayName\":\""+invalid+"\"}"),"invalid display name rejected");
        status(400,post(browser,"/api/account/display-name","{\"displayName\":\"bad\\u0001name\"}"),"control character rejected");
        status(400,post(browser,"/api/account/display-name","{\"displayName\":\"Attacker\",\"username\":\""+other.username()+"\"}"),"identity override rejected");
        assertEquals(twoHundredUtf16Units,json(browser.get("/api/account")).get("displayName"),"rejected writes preserve the current name");
        try(var c=connection();var s=c.prepareStatement("SELECT display_name FROM app_accounts WHERE id=?")) {
            s.setObject(1,other.id()); try(var rows=s.executeQuery()) { assertTrue(rows.next()); assertEquals("Acceptance DEVELOPER",rows.getString(1)); }
        }
    }

    @Test void displayNameChangeRequiresCsrfAndDemoAccountRemainsLocked() throws Exception {
        Account reporter=account("REPORTER"); Browser browser=authenticated(reporter);
        status(403,browser.request("POST","/api/account/display-name","{\"displayName\":\"Changed\"}","Content-Type","application/json"),"display name requires CSRF");
        assertEquals("Acceptance REPORTER",json(browser.get("/api/account")).get("displayName"));

        Browser demoBrowser=authenticated(demo());
        status(403,post(demoBrowser,"/api/account/display-name","{\"displayName\":\"Changed demo\"}"),"demo identity is immutable");
        assertEquals("Demo Maintainer",json(demoBrowser.get("/api/account")).get("displayName"));
        assertEquals("Demo Maintainer",json(demoBrowser.get("/api/auth/me")).get("displayName"));
    }

    @Test void anonymousAndInactiveAccountsCannotChangeDisplayName() throws Exception {
        Browser anonymous=new Browser(app);
        status(401,post(anonymous,"/api/account/display-name","{\"displayName\":\"Anonymous edit\"}"),"anonymous account denied");

        Account inactive=account("DEVELOPER"); Browser browser=authenticated(inactive);
        update(inactive,"active=false");
        status(403,post(browser,"/api/account/display-name","{\"displayName\":\"Inactive edit\"}"),"inactive account denied");
        try(var c=connection();var s=c.prepareStatement("SELECT display_name FROM app_accounts WHERE id=?")) {
            s.setObject(1,inactive.id()); try(var rows=s.executeQuery()) { assertTrue(rows.next()); assertEquals("Acceptance DEVELOPER",rows.getString(1)); }
        }
    }
}
