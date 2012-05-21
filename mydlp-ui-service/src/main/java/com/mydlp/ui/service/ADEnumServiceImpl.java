package com.mydlp.ui.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.mydlp.ui.dao.ADDomainDAO;
import com.mydlp.ui.domain.ADDomain;
import com.mydlp.ui.domain.ADDomainGroup;
import com.mydlp.ui.domain.ADDomainItem;
import com.mydlp.ui.domain.ADDomainItemGroup;
import com.mydlp.ui.domain.ADDomainOU;
import com.mydlp.ui.domain.ADDomainRoot;
import com.mydlp.ui.domain.ADDomainUser;
import com.mydlp.ui.domain.ADDomainUserAlias;

@Service("adEnumService")
public class ADEnumServiceImpl implements ADEnumService {

	private static Logger logger = LoggerFactory.getLogger(ADEnumService.class);
	
	protected Set<Integer> currentlyProcessingDomains = new HashSet<Integer>();
	
	protected Map<Integer, String> messageMap = new HashMap<Integer,String>();
	
	@Autowired
	protected ADDomainDAO adDomainDAO;

	@Autowired
	@Qualifier("policyTransactionTemplate")
	protected TransactionTemplate transactionTemplate;
	
	@Async
	public void enumerate(final ADDomain domain) {
		transactionTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus arg0) {
				enumerateFun(domain);
			}
		});
	}
		
	public void enumerateFun(ADDomain domain) {
		if (currentlyProcessingDomains.contains(domain.getId()))
		{
			logger.info("Enumerating already scheduled for domain.", domain.getDomainName());
			return;
		}
		currentlyProcessingDomains.add(domain.getId());
		
		String distinguishedName = fqdnToLdapdn(domain.getDomainName());
		if (domain.getRoot() == null) {
			ADDomainRoot root = new ADDomainRoot();
			root.setDistinguishedName("mydlp-domain-root/" + domain.getDomainName());
			root.setDomain(domain);
			root = (ADDomainRoot) adDomainDAO.saveDomainItem(root);
			domain.setRoot(root);
			domain = adDomainDAO.saveDomain(domain);
		}
		
		try {
			domain.setCurrentlyEnumerating(true);
			domain.setMessage("");
			domain = (ADDomain) adDomainDAO.merge(domain);
			Map<String, Set<String>> memberships = initMemberships();
			enumerateDN(domain, domain.getRoot(), distinguishedName, memberships);
			enumerateMemberships(memberships);
			adDomainDAO.finalizeProcess(domain.getId(), "");
		} catch (RuntimeException e) {
			processError(domain, e);
		} catch (Throwable e) {
			processError(domain, e);
		} finally {
			currentlyProcessingDomains.remove(domain.getId());
		}
	}
	
	protected Map<String, Set<String>> initMemberships() {
		Map<String, Set<String>> memberships = new HashMap<String, Set<String>>();
		return memberships;
	}
	
	protected void addToMemberships(Map<String, Set<String>> memberships, String userDN, String groupDN) {
		if (!memberships.containsKey(userDN)) {
			memberships.put(userDN, new HashSet<String>());
		}
		Set<String> groupDNs = memberships.get(userDN);
		groupDNs.add(groupDN);
	}
	
	protected void addToMemberships(Map<String, Set<String>> memberships, String userDN, Set<String> groups) {
		for (String groupDN: groups) {
			addToMemberships(memberships, userDN, groupDN);
		}
	}
	
	protected void enumerateMemberships(Map<String, Set<String>> memberships) {
		for (String userDN : memberships.keySet()) {
			enumerateUserMembership(userDN, memberships.get(userDN));
		}
	}

	protected void enumerateUserMembership(String userDN, Set<String> groupDNs) {
		ADDomainUser userObject = (ADDomainUser) adDomainDAO.findByDistinguishedName(userDN);
		Set<ADDomainGroup> dummy = new HashSet<ADDomainGroup>();
		if (userObject.getGroups() != null)
			dummy.addAll(userObject.getGroups());
		for (String groupDN : groupDNs) {
			ADDomainGroup groupObject = (ADDomainGroup) adDomainDAO.findByDistinguishedName(groupDN);
			dummy.remove(groupObject);
			if (userObject.getGroups() == null || !userObject.getGroups().contains(groupObject)) {
				if (userObject.getGroups() == null)
					userObject.setGroups(new HashSet<ADDomainGroup>());
				userObject.getGroups().add(groupObject);
				if (groupObject.getUsers() == null)
					groupObject.setUsers(new HashSet<ADDomainUser>());
				groupObject.getUsers().add(userObject);
				adDomainDAO.saveDomainItem(groupObject);
				adDomainDAO.saveDomainItem(userObject);
			}
		}
		
		for (ADDomainGroup exGroup : dummy) {
			exGroup.getUsers().remove(userObject);
			userObject.getGroups().remove(exGroup);
			adDomainDAO.saveDomainItem(exGroup);
			adDomainDAO.saveDomainItem(userObject);
		}
	}
	
	protected void processError(ADDomain domain, Throwable e) {
		logger.error("Error occured", e);
		adDomainDAO.finalizeProcess(domain.getId(), e.getMessage());
		messageMap.put(domain.getId(), e.getMessage());
		//throw new RuntimeException(e);
	}
	
	protected DirContext context(ADDomain domain) throws NamingException {
		Hashtable<String, String> env = new Hashtable<String, String>();
		env.put(Context.INITIAL_CONTEXT_FACTORY,
				"com.sun.jndi.ldap.LdapCtxFactory");
		env.put(Context.PROVIDER_URL, "ldap://" + domain.getServerIp() + ":389");
		env.put(Context.SECURITY_PRINCIPAL, domain.getLoginUsername());
		env.put(Context.SECURITY_CREDENTIALS, domain.getLoginPassword());
		env.put(Context.REFERRAL, "follow");
		DirContext ctx = new InitialDirContext(env);
		return ctx;
	}
	
	protected void enumerateDN(ADDomain domain, ADDomainItemGroup parent, String distinguishedName, Map<String, Set<String>> memberships) throws NamingException {
		List<ADDomainItem> children = adDomainDAO.getChildrenOf(parent);
		List<ADDomainItem> enumeratedUsers = searchDNforUsers(domain, parent, distinguishedName, memberships);
		List<ADDomainItem> enumeratedGroups = searchDNforGroups(domain, parent, distinguishedName);
		List<ADDomainItem> enumeratedOUs = searchDNforOU(domain, parent, distinguishedName, memberships);
		
		List<ADDomainItem> dummy = new ArrayList<ADDomainItem>(); 
		dummy.addAll(children);
		
		dummy.removeAll(enumeratedUsers);
		dummy.removeAll(enumeratedGroups);
		dummy.removeAll(enumeratedOUs);
		
		for (ADDomainItem adDomainItem : dummy) {
			try {
				adDomainDAO.remove(adDomainItem);
			} catch (UncategorizedSQLException e) {
				logger.error("Does not removing ADDomainItem, because item is used in inventory: ", adDomainItem.getId());
			} catch (Throwable e) {
				logger.error("Error occurred when trying to remove domain item", e);
			}
		}
	}
	
	protected List<ADDomainItem> searchDNforUsers(ADDomain domain, ADDomainItemGroup parent, String distinguishedName, Map<String, Set<String>> memberships) throws NamingException {
		DirContext ctx = context(domain);
		SearchControls ctls = new SearchControls();
		String[] attrIDs =  { "displayName", "sAMAccountName", "distinguishedName", "proxyAddresses", "mailNickname","memberOf"};
		ctls.setReturningAttributes(attrIDs);
		
		ctls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
		NamingEnumeration<SearchResult> queryResults = ctx.search(distinguishedName,
				"(&(objectCategory=person)(objectClass=user))"
				, ctls);
		return saveUsers(parent, queryResults, memberships);
	}
	
	protected List<ADDomainItem> searchDNforOU(ADDomain domain, ADDomainItemGroup parent, String distinguishedName, Map<String, Set<String>> memberships) throws NamingException {
		DirContext ctx = context(domain);
		SearchControls ctls = new SearchControls();
		String[] attrIDs =  { "name", "distinguishedName" };
		ctls.setReturningAttributes(attrIDs);
		
		ctls.setSearchScope(SearchControls.ONELEVEL_SCOPE);

		NamingEnumeration<SearchResult> queryResults = ctx.search(distinguishedName,
				"(&(objectClass=organizationalUnit)(!(isCriticalSystemObject=TRUE))(!(msExchVersion=*)))"
				, ctls);
		
		return saveOUs(domain, parent, queryResults, memberships);
	}

	protected List<ADDomainItem> searchDNforGroups(ADDomain domain, ADDomainItemGroup parent, String distinguishedName) throws NamingException {
		DirContext ctx = context(domain);
		SearchControls ctls = new SearchControls();
		String[] attrIDs =  { "name", "distinguishedName" };
		ctls.setReturningAttributes(attrIDs);
		
		ctls.setSearchScope(SearchControls.ONELEVEL_SCOPE);

		NamingEnumeration<SearchResult> queryResults = ctx.search(distinguishedName,
				"(&(objectClass=group))"
				, ctls);
		
		return saveGroups(domain, parent, queryResults);
	}

	protected String fqdnToLdapdn(String domainName) {
		String arr[] = domainName.split("\\.");
		List<String> dcList = new ArrayList<String>(8);
		for (String s : arr) {
			dcList.add("dc=" + s);
		}
		return StringUtils.join(dcList, ",");
	}
	
	protected List<ADDomainItem> saveUsers(ADDomainItemGroup parent, NamingEnumeration<SearchResult> queryResults, Map<String, Set<String>> memberships) throws NamingException {
		
		List<ADDomainItem> resultList = new ArrayList<ADDomainItem>();
		
		while (queryResults.hasMoreElements())
		{
			SearchResult result = queryResults.next();
			Attributes attribs = result.getAttributes();

			if (null != attribs)
			{
				// "displayName", "sAMAccountName", "distinguishedName", "proxyAddresses","mailNickname","memberOf"
				String displayName = null;
				String sAMAccountName = null;
				String distinguishedName = null;
				
				Set<String> userAliases = new HashSet<String>();
				Set<String> groups = new HashSet<String>();
				
				
				for (NamingEnumeration<? extends Attribute> ae = attribs.getAll(); ae.hasMoreElements();)
				{
					Attribute atr = (Attribute) ae.next();
					String attributeID = atr.getID();
					
					//for (int i = 0; i < atr.size(); i++) 
					//	System.out.println(attributeID + ": " + atr.get(i));
					
					if (atr.size() > 0)
					{
						if (attributeID.equals("displayName"))
							displayName = (String) atr.get(0);
						else if (attributeID.equals("sAMAccountName"))
							sAMAccountName = ((String) atr.get(0)).toLowerCase();
						else if (attributeID.equals("distinguishedName"))
							distinguishedName = (String) atr.get(0);
						else if (attributeID.equals("proxyAddresses"))
							for (int i = 0; i < atr.size(); i++) {
								String val = (String) atr.get(i);
								val = val.toLowerCase();
								if (!val.startsWith("smtp:"))
									continue;
								val = val.substring(5); // Drop smtp:
								int atSignIndex = val.indexOf('@');
								val = val.substring(0, atSignIndex); // drop @domain.com
								userAliases.add(val);
							}
						else if (attributeID.equals("mailNickname"))
							for (int i = 0; i < atr.size(); i++) {
								String val = (String) atr.get(i);
								val = val.toLowerCase();
								userAliases.add(val);
							}
						else if (attributeID.equals("memberOf"))
							groups.add((String) atr.get(0));
					}
				}
		
				ADDomainUser domainUser = (ADDomainUser) adDomainDAO.findByDistinguishedName(distinguishedName);
				Boolean saveFlag = false;
				
				if (domainUser == null) {
					domainUser = new ADDomainUser();
					domainUser.setParent(parent);
					domainUser.setDistinguishedName(distinguishedName);
					saveFlag = true;
				}
				
				if (!displayName.equals(domainUser.getDisplayName())) {
					domainUser.setDisplayName(displayName);
					saveFlag = true;
				}
				
				if (!sAMAccountName.equals(domainUser.getsAMAccountName())) {
					domainUser.setsAMAccountName(sAMAccountName);
					saveFlag = true;
				}
				
				
				userAliases.remove(sAMAccountName);
				
				List<ADDomainUserAlias> aliasesToSave = null;
				List<ADDomainUserAlias> aliasesToDelete = new ArrayList<ADDomainUserAlias>();
				if (domainUser.getAliases() == null )
					aliasesToSave = new ArrayList<ADDomainUserAlias>();
				else
					aliasesToSave = domainUser.getAliases();
				
				for (ADDomainUserAlias adDomainUserAlias : aliasesToSave) {
					if (!userAliases.remove(adDomainUserAlias.getUserAlias()))
					{
						aliasesToSave.remove(adDomainUserAlias);
						aliasesToDelete.add(adDomainUserAlias);
						saveFlag = true;
					}
				}
				
				for (String aliasStr : userAliases) {
					ADDomainUserAlias uaObj = new ADDomainUserAlias();
					uaObj.setUserAlias(aliasStr);
					aliasesToSave.add(uaObj);
					saveFlag = true;
				}
				
				domainUser.setAliases(aliasesToSave);
				if (saveFlag)
					adDomainDAO.saveDomainItem(domainUser);
				for (ADDomainUserAlias adDomainUserAlias : aliasesToDelete) {
					adDomainDAO.remove(adDomainUserAlias);
				}
				
				addToMemberships(memberships, distinguishedName, groups);
				resultList.add(domainUser);
			}
		}
		
		return resultList;
	}
	
	protected List<ADDomainItem> saveOUs(ADDomain domain, ADDomainItemGroup parent, NamingEnumeration<SearchResult> queryResults, Map<String, Set<String>> memberships) throws NamingException {
		List<ADDomainItem> resultList = new ArrayList<ADDomainItem>();
		
		while (queryResults.hasMoreElements())
		{
			SearchResult result = queryResults.next();
			Attributes attribs = result.getAttributes();

			if (null != attribs)
			{
				String name = null;
				String distinguishedName = null;
				
				for (NamingEnumeration<? extends Attribute> ae = attribs.getAll(); ae.hasMoreElements();)
				{
					Attribute atr = (Attribute) ae.next();
					String attributeID = atr.getID();
					
					//for (int i = 0; i < atr.size(); i++) 
					//	System.out.println(attributeID + ": " + atr.get(i));
					
					if (atr.size() > 0)
					{
						if (attributeID.equals("name"))
							name = (String) atr.get(0);
						else if (attributeID.equals("distinguishedName"))
							distinguishedName = (String) atr.get(0);
					}
				}
				
				ADDomainOU domainOU = (ADDomainOU) adDomainDAO.findByDistinguishedName(distinguishedName);
				Boolean saveFlag = false;
				
				if (domainOU == null) {
					domainOU = new ADDomainOU();
					domainOU.setParent(parent);
					domainOU.setDistinguishedName(distinguishedName);
					saveFlag=true;
				}
				
				if (!name.equals(domainOU.getName())) {
					domainOU.setName(name);
					saveFlag = true;
				}
				
				if (saveFlag)
					domainOU = (ADDomainOU) adDomainDAO.saveDomainItem(domainOU);
				
				enumerateDN(domain, domainOU, domainOU.getDistinguishedName(), memberships);
				resultList.add(domainOU);
			}
		}
		
		return resultList;
	}
	
	protected List<ADDomainItem> saveGroups(ADDomain domain, ADDomainItemGroup parent, NamingEnumeration<SearchResult> queryResults) throws NamingException {
		List<ADDomainItem> resultList = new ArrayList<ADDomainItem>();
		
		while (queryResults.hasMoreElements())
		{
			SearchResult result = queryResults.next();
			Attributes attribs = result.getAttributes();

			if (null != attribs)
			{
				String name = null;
				String distinguishedName = null;
				
				for (NamingEnumeration<? extends Attribute> ae = attribs.getAll(); ae.hasMoreElements();)
				{
					Attribute atr = (Attribute) ae.next();
					String attributeID = atr.getID();
					
					//for (int i = 0; i < atr.size(); i++) 
					//	System.out.println(attributeID + ": " + atr.get(i));
					
					if (atr.size() > 0)
					{
						if (attributeID.equals("name"))
							name = (String) atr.get(0);
						else if (attributeID.equals("distinguishedName"))
							distinguishedName = (String) atr.get(0);
					}
				}
				
				ADDomainGroup domainGroup = (ADDomainGroup) adDomainDAO.findByDistinguishedName(distinguishedName);
				Boolean saveFlag = false;
				
				if (domainGroup == null) {
					domainGroup = new ADDomainGroup();
					domainGroup.setParent(parent);
					domainGroup.setDistinguishedName(distinguishedName);
					saveFlag=true;
				}
				
				if (!name.equals(domainGroup.getName())) {
					domainGroup.setName(name);
					saveFlag = true;
				}
				
				if (saveFlag)
					domainGroup = (ADDomainGroup) adDomainDAO.saveDomainItem(domainGroup);
				
				resultList.add(domainGroup);
			}
		}
		
		return resultList;
	}

	@Override
	public String getLatestMessage(Integer domainId) {
		if (messageMap.containsKey(domainId))
			return messageMap.get(domainId);
		return "";
	}
	
}
