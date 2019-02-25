package io.github.johnjcool.keycloak.broker.cas;


import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.broker.provider.AbstractIdentityProvider;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;

import static io.github.johnjcool.keycloak.broker.cas.util.UrlHelper.PROVIDER_PARAMETER_STATE;
import static io.github.johnjcool.keycloak.broker.cas.util.UrlHelper.PROVIDER_PARAMETER_TICKET;
import static io.github.johnjcool.keycloak.broker.cas.util.UrlHelper.createAuthenticationUrl;
import static io.github.johnjcool.keycloak.broker.cas.util.UrlHelper.createLogoutUrl;
import static io.github.johnjcool.keycloak.broker.cas.util.UrlHelper.createValidateServiceUrl;

public class CasIdentityProvider extends AbstractIdentityProvider<CasIdentityProviderConfig> {

	protected static final Logger logger = Logger.getLogger(CasIdentityProvider.class);
	protected static final Logger LOGGER_DUMP_USER_PROFILE = Logger.getLogger("org.keycloak.social.user_profile_dump");

	public static final String USER_ATTRIBUTES = "UserAttributes";

	private final Client client;

	public CasIdentityProvider(final KeycloakSession session, final CasIdentityProviderConfig config) {
		super(session, config);
		client = ResteasyClientBuilder.newClient(ResteasyProviderFactory.getInstance());
	}

	@Override
	public Response performLogin(final AuthenticationRequest request) {
		try {
			URI authenticationUrl = createAuthenticationUrl(getConfig(), request).build();
			return Response.seeOther(authenticationUrl).build();
		} catch (Exception e) {
			throw new IdentityBrokerException("Could send authentication request to cas provider.", e);
		}
	}

	@Override
	public Response keycloakInitiatedBrowserLogout(final KeycloakSession session, final UserSessionModel userSession, final UriInfo uriInfo,
			final RealmModel realm) {
		URI logoutUrl = createLogoutUrl(getConfig(), userSession, realm, uriInfo).build();
		return Response.status(302).location(logoutUrl).build();
	}

	@Override
	public Response retrieveToken(final KeycloakSession session, final FederatedIdentityModel identity) {
		return Response.ok(identity.getToken()).type(MediaType.APPLICATION_JSON).build();
	}

	@Override
	public Object callback(final RealmModel realm, final AuthenticationCallback callback,
			final EventBuilder event) {
		return new Endpoint(callback, realm, event);
	}

	public final class Endpoint {
		AuthenticationCallback callback;
		RealmModel realm;
		EventBuilder event;

		@Context
		protected KeycloakSession session;

		@Context
		protected ClientConnection clientConnection;

		@Context
		protected HttpHeaders headers;

		@Context
		protected UriInfo uriInfo;

		Endpoint(final AuthenticationCallback callback, final RealmModel realm, final EventBuilder event) {
			this.callback = callback;
			this.realm = realm;
			this.event = event;
		}

		@GET
		public Response authResponse(@QueryParam(PROVIDER_PARAMETER_TICKET) final String ticket, @QueryParam(PROVIDER_PARAMETER_STATE) final String state) {
			try {
				CasIdentityProviderConfig config = CasIdentityProvider.this.getConfig();
				BrokeredIdentityContext federatedIdentity = getFederatedIdentity(CasIdentityProvider.this.client, config, ticket, uriInfo, state);

				return callback.authenticated(federatedIdentity);
			} catch (Exception e) {
				logger.error("Failed to call delegating authentication identity provider's callback method.", e);
			}
			event.event(EventType.LOGIN);
			event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
			return ErrorPage.error(session, null, Status.EXPECTATION_FAILED, Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
		}

		@GET
		@Path("logout_response")
		public Response logoutResponse(@Context final UriInfo uriInfo, @QueryParam("state") final String state) {
			UserSessionModel userSession = session.sessions().getUserSession(realm, state);
			if (userSession == null) {
				logger.error("no valid user session");
				EventBuilder e = new EventBuilder(realm, session, clientConnection);
				e.event(EventType.LOGOUT);
				e.error(Errors.USER_SESSION_NOT_FOUND);
				return ErrorPage.error(session, null, Status.BAD_REQUEST, Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
			}
			if (userSession.getState() != UserSessionModel.State.LOGGING_OUT) {
				logger.error("usersession in different state");
				EventBuilder e = new EventBuilder(realm, session, clientConnection);
				e.event(EventType.LOGOUT);
				e.error(Errors.USER_SESSION_NOT_FOUND);
				return ErrorPage.error(session, null, Status.BAD_REQUEST, Messages.SESSION_NOT_ACTIVE);
			}
			return AuthenticationManager.finishBrowserLogout(session, realm, userSession, uriInfo, clientConnection, headers);
		}

		private BrokeredIdentityContext getFederatedIdentity(final Client client, final CasIdentityProviderConfig config, final String ticket,
				final UriInfo uriInfo, final String state) {
			Response response = null;
			try {

				WebTarget target = client.target(createValidateServiceUrl(config, ticket, uriInfo, state));
				//response = target.request(MediaType.APPLICATION_XML_TYPE).get();
				response = target.request(MediaType.APPLICATION_XML_TYPE).get();
				if (response.getStatus() != 200) {
					throw new Exception("Failed : HTTP error code : " + response.getStatus());
				}
				//String entitys = (String) response.getEntity();

				InputStream in= (InputStream)response.getEntity();

				ByteArrayOutputStream baos = new ByteArrayOutputStream();


				byte[] buffer = new byte[1024];

				int len = 0;

				while ((len = in.read(buffer)) != -1) {
					baos.write(buffer, 0, len);
				}

				in.close();

				String entitys = baos.toString();

				baos.close();

				System.out.println("reslut:>>>>>>>>>>>" + entitys);

				String userName;
				if (entitys.equals("no\n\n")) {
					throw new Exception("用户信息验证失败");
				} else {
					String[] items = entitys.split("\n");
					userName = items[1];
				}

				System.out.println("login user:>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>" + userName);
				BrokeredIdentityContext user = new BrokeredIdentityContext(userName);
				user.setUsername(userName);
				//user.getContextData().put(USER_ATTRIBUTES, success.getAttributes());
				user.setIdpConfig(config);
				user.setIdp(CasIdentityProvider.this);
				user.setCode(state);
				return user;
			} catch (Exception e) {
				throw new IdentityBrokerException("Could not fetch attributes from External IdP's userinfo endpoint.", e);
			} finally {
				if (response != null) {
					response.close();
				}
			}
		}
	}
}
