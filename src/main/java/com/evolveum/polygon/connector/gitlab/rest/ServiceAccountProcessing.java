/**
 * Copyright (c) 2010-2017 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.*;

public class ServiceAccountProcessing extends ObjectProcessing {

	private static final String STATUS_ACTIVE = "active";

	// optional attributes
	private static final String ATTR_MAIL = "email";
	private static final String ATTR_PUBLIC_EMAIL = "public_email";
	private static final String ATTR_GROUP_ID = "groupId";

	public ServiceAccountProcessing(GitlabRestConfiguration configuration, CloseableHttpClient httpclient) {
		super(configuration, httpclient);
	}

	public void buildServiceAccountObjectClass(SchemaBuilder schemaBuilder) {
		ObjectClassInfoBuilder serviceAccountObjClassBuilder = new ObjectClassInfoBuilder();

		serviceAccountObjClassBuilder.setType(SERVICE_ACCOUNT_NAME);

		// createable: TRUE && updateable: TRUE && readable: TRUE
		AttributeInfoBuilder attrMailBuilder = new AttributeInfoBuilder(ATTR_MAIL);
		attrMailBuilder.setRequired(true).setType(String.class).setCreateable(true).setUpdateable(true)
				.setReadable(true);
		serviceAccountObjClassBuilder.addAttributeInfo(attrMailBuilder.build());

		AttributeInfoBuilder attrNameBuilder = new AttributeInfoBuilder(ATTR_NAME);
		attrNameBuilder.setRequired(true).setType(String.class).setCreateable(true).setUpdateable(true)
				.setReadable(true);
		serviceAccountObjClassBuilder.addAttributeInfo(attrNameBuilder.build());

		AttributeInfoBuilder attrGroupIdBuilder = new AttributeInfoBuilder(ATTR_GROUP_ID);
		attrGroupIdBuilder.setRequired(true).setType(Integer.class).setCreateable(true).setUpdateable(true)
				.setReadable(true);
		serviceAccountObjClassBuilder.addAttributeInfo(attrGroupIdBuilder.build());

		AttributeInfoBuilder attrPublicEmailBuilder = new AttributeInfoBuilder(ATTR_PUBLIC_EMAIL);
		attrPublicEmailBuilder.setRequired(true).setType(String.class).setCreateable(true).setUpdateable(true)
				.setReadable(true);
		serviceAccountObjClassBuilder.addAttributeInfo(attrPublicEmailBuilder.build());


		// __ENABLE__
		/*serviceAccountObjClassBuilder.addAttributeInfo(OperationalAttributeInfos.ENABLE);*/

		schemaBuilder.defineObjectClass(serviceAccountObjClassBuilder.build());
	}

	public Uid createOrUpdateServiceAccount(Uid uid,
											Set<Attribute> attributes,
											OperationOptions options) {
		boolean create = (uid == null);
		JSONObject json = new JSONObject();

		// 1) Mandatory: "name"
		putRequestedAttrIfExists(create, attributes, ATTR_NAME, json);

		// 2) Optional: "username"
		putOptionalUsername(attributes, json);

		// 3) Optional: "email"
		putAttrIfExists(attributes, ATTR_MAIL, String.class, json);

		// 4) Optional: "public_email"
		putAttrIfExists(attributes, ATTR_PUBLIC_EMAIL, String.class, json);

		if (create) {
			// POST /service_accounts
			return createPutOrPostRequest(null, SERVICE_ACCOUNTS, json, true);
		} else {
			// PATCH /users/{id} //direct support for service_account endpoint not released yet
			return createPutOrPostRequest(uid, USERS, json,false);
		}
	}

	private ConnectorObjectBuilder convertUserJSONObjectToConnectorObject(JSONObject user, Set<String> sshKeys,
			byte[] avatarPhoto, List<String> identities) {
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
		builder.setObjectClass(new ObjectClass(SERVICE_ACCOUNT_NAME));

		getUIDIfExists(user, UID, builder);

		getNAMEIfExists(user, ATTR_USERNAME, builder);

		getIfExists(user, ATTR_MAIL, String.class, builder);
		getIfExists(user, ATTR_NAME, String.class, builder);

		if (user.has("provisioned_by_group_id") && !user.isNull("provisioned_by_group_id")) {
			Integer groupId = ((Number) user.get("provisioned_by_group_id")).intValue();
			builder.addAttribute(ATTR_GROUP_ID, groupId);
		}

		return builder;
	}



	private Map<String, Integer> getSSHKeysAsMap(int userUid) {

		URIBuilder uriBuilder = getURIBuilder();
		StringBuilder path = new StringBuilder();
		path.append(USERS).append("/").append(userUid).append(KEYS);

		JSONArray objectsSSHKeys = new JSONArray();
		JSONArray partOfsSSHKeys = new JSONArray();
		int ii = 1;
		uriBuilder.setPath(path.toString());

		do {
			uriBuilder.clearParameters();
			uriBuilder.addParameter(PAGE, String.valueOf(ii));
			uriBuilder.addParameter(PER_PAGE, "100");
			HttpRequestBase requestSSHKey;
			try {
				requestSSHKey = new HttpGet(uriBuilder.build());
			} catch (URISyntaxException e) {
				StringBuilder sb = new StringBuilder();
				sb.append("It was not possible create URI from UriBuilder; ").append(e.getLocalizedMessage());
				throw new ConnectorException(sb.toString(), e);
			}
			partOfsSSHKeys = callRequestForJSONArray(requestSSHKey, true);
			Iterator<Object> iterator = partOfsSSHKeys.iterator();
			while (iterator.hasNext()) {
				Object sshKey = iterator.next();
				objectsSSHKeys.put(sshKey);
			}
			ii++;
		} while (partOfsSSHKeys.length() == 100);

		Map<String, Integer> sshKeys = new HashMap<String, Integer>();
		for (int i = 0; i < objectsSSHKeys.length(); i++) {
			JSONObject jsonObjectMember = objectsSSHKeys.getJSONObject(i);
			String sshKey = ((String) jsonObjectMember.get("key"));
			String unescapesshKey = StringEscapeUtils.unescapeXml(sshKey);
			sshKeys.put(unescapesshKey, ((Integer) jsonObjectMember.get(UID)));
		}
		return sshKeys;
	}

	public void executeQueryForServiceAccount(Filter query,
											  ResultsHandler handler,
											  OperationOptions options) {

		Boolean useDefaultEndpoint = false;
		Set<Integer> allowedGroupIds = lookupAllowedGroupIds(options);
		if (allowedGroupIds.isEmpty()) {
			LOGGER.info("No groups configured or none found in GitLab.");
			useDefaultEndpoint = true;
		}
		// 1) EqualsFilter on Uid: GET /users/{id}
		if (query instanceof EqualsFilter
				&& ((EqualsFilter) query).getAttribute() instanceof Uid) {

			Uid uid = (Uid) ((EqualsFilter) query).getAttribute();
			if (uid.getUidValue() == null) {
				invalidAttributeValue(Uid.NAME, query);
			}
			String path = USERS + "/" + uid.getUidValue();
			JSONObject svc = (JSONObject) executeGetRequest(path, null, options, false);

			boolean hasGroup = svc.has("provisioned_by_group_id")
					&& !svc.isNull("provisioned_by_group_id");
			Integer groupId = null;
			if (hasGroup) {
				groupId = svc.getInt("provisioned_by_group_id");
			}

			if ( useDefaultEndpoint
					|| (hasGroup && allowedGroupIds.contains(groupId)) ) {
				processingObjectFromGET(svc, handler);
			}

			// 2) EqualsFilter on Name: GET /users?username={name}
		} else if (query instanceof EqualsFilter
				&& ((EqualsFilter) query).getAttribute() instanceof Name) {

			List<Object> vals = ((EqualsFilter) query).getAttribute().getValue();
			if (vals == null || vals.get(0) == null) {
				invalidAttributeValue(Name.NAME, query);
			}
			String username = vals.get(0).toString();
			Map<String,String> params = new HashMap<>();
			params.put("username", username);
			JSONArray list = (JSONArray) executeGetRequest(USERS, params, options, true);

			for (Object o : list) {
				JSONObject svc = (JSONObject) o;

				boolean hasGroup = svc.has("provisioned_by_group_id")
						&& !svc.isNull("provisioned_by_group_id");
				Integer groupId = null;
				if (hasGroup) {
					groupId = svc.getInt("provisioned_by_group_id");
				}

				if ( useDefaultEndpoint
						|| (hasGroup && allowedGroupIds.contains(groupId)) ) {
					processingObjectFromGET(svc, handler);
				}
			}

			// 3) No filter: list all service accounts
		} else if (query == null) {
			if (useDefaultEndpoint){
				JSONArray list = (JSONArray) executeGetRequest(SERVICE_ACCOUNTS, null, options, true);
				processingObjectFromGET(list, handler);
			} else {
				for (Integer groupId : allowedGroupIds) {
					String path = GROUPS + "/" + groupId + SERVICE_ACCOUNTS;
					JSONArray saList = (JSONArray) executeGetRequest(
							path, null, options, true);
					for (Object o : saList) {
						JSONObject sa = (JSONObject) o;
						sa.put("provisioned_by_group_id", groupId);
					}
					processingObjectFromGET(saList, handler);
				}
			}
		} else {
			String msg = "Unsupported filter for serviceAccount: " + query;
			LOGGER.error(msg);
			throw new UnsupportedOperationException(msg);
		}
	}


	private void processingObjectFromGET(JSONObject user, ResultsHandler handler) {
		byte[] avaratPhoto = getAvatarPhoto(user, ATTR_AVATAR_URL, ATTR_AVATAR);
		int userUidValue = getUIDIfExists(user, UID);
		Set<String> SSHKeys = getSSHKeysAsMap(userUidValue).keySet();
		/*List<String> identities = getAttributeForIdentities(user);*/
		ConnectorObjectBuilder builder = convertUserJSONObjectToConnectorObject(user, SSHKeys, avaratPhoto, null);
		ConnectorObject connectorObject = builder.build();
		LOGGER.info("convertUserToConnectorObject, user: {0}, \n\tconnectorObject: {1}", user.get(UID),
				connectorObject.toString());
		handler.handle(connectorObject);
	}

	private void processingObjectFromGET(JSONArray users, ResultsHandler handler) {
		JSONObject user;
		for (int i = 0; i < users.length(); i++) {
			user = users.getJSONObject(i);
			processingObjectFromGET(user, handler);
		}
	}

	private Map<String, String> getGroupsForFilter(String groupsToManage) {
		LOGGER.info("getGroupsForFilter Start");
		Map<String, String> groupArr = new HashMap<String, String>();
		if (groupsToManage == null || groupsToManage.isEmpty()) {
			return null;
		}
		String[] values = groupsToManage.toLowerCase().split(",");
		for (String value : values) {
			groupArr.put(value, value);
		}
		LOGGER.info("getGroupsForFilter End");
		return groupArr;
	}

	private Set<Integer> lookupAllowedGroupIds(OperationOptions options) {
		// 1) split & normalize the names
		Map<String,String> nameMap = getGroupsForFilter(configuration.getGroupsToManage());
		if (nameMap == null || nameMap.isEmpty()) {
			return Collections.emptySet();
		}

		JSONArray allGroups = (JSONArray) executeGetRequest(GROUPS, null, options, true);

		Set<Integer> ids = new HashSet<>();
		for (Object o : allGroups) {
			JSONObject g = (JSONObject) o;
			String gitlabName = ((String) g.get("name")).toLowerCase();
			String gitlabPath = ((String) g.get("path")).toLowerCase();
			if (nameMap.containsKey(gitlabName) || nameMap.containsKey(gitlabPath)) {
				ids.add(((Number) g.get("id")).intValue());
			}
		}
		return ids;
	}
}