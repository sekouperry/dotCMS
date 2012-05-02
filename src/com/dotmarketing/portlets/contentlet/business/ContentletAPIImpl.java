/**
 *
 */
package com.dotmarketing.portlets.contentlet.business;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.queryParser.ParseException;

import com.dotcms.enterprise.cmis.QueryResult;
import com.dotmarketing.beans.Host;
import com.dotmarketing.beans.Identifier;
import com.dotmarketing.beans.MultiTree;
import com.dotmarketing.beans.Permission;
import com.dotmarketing.beans.Tree;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.business.DotStateException;
import com.dotmarketing.business.FactoryLocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.business.RelationshipAPI;
import com.dotmarketing.business.Role;
import com.dotmarketing.business.query.GenericQueryFactory.Query;
import com.dotmarketing.business.query.QueryUtil;
import com.dotmarketing.business.query.ValidationException;
import com.dotmarketing.cache.FieldsCache;
import com.dotmarketing.cache.IdentifierCache;
import com.dotmarketing.cache.StructureCache;
import com.dotmarketing.common.business.journal.DistributedJournalAPI;
import com.dotmarketing.common.model.ContentletSearch;
import com.dotmarketing.common.reindex.ReindexThread;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.factories.IdentifierFactory;
import com.dotmarketing.factories.InodeFactory;
import com.dotmarketing.factories.MultiTreeFactory;
import com.dotmarketing.factories.PublishFactory;
import com.dotmarketing.factories.TreeFactory;
import com.dotmarketing.portlets.categories.business.CategoryAPI;
import com.dotmarketing.portlets.categories.model.Category;
import com.dotmarketing.portlets.containers.model.Container;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.contentlet.model.ContentletAndBinary;
import com.dotmarketing.portlets.files.factories.FileFactory;
import com.dotmarketing.portlets.files.model.File;
import com.dotmarketing.portlets.folders.business.FolderAPI;
import com.dotmarketing.portlets.folders.factories.FolderFactory;
import com.dotmarketing.portlets.folders.model.Folder;
import com.dotmarketing.portlets.htmlpages.factories.HTMLPageFactory;
import com.dotmarketing.portlets.htmlpages.model.HTMLPage;
import com.dotmarketing.portlets.languagesmanager.business.LanguageAPI;
import com.dotmarketing.portlets.languagesmanager.model.Language;
import com.dotmarketing.portlets.links.model.Link;
import com.dotmarketing.portlets.structure.business.FieldAPI;
import com.dotmarketing.portlets.structure.factories.FieldFactory;
import com.dotmarketing.portlets.structure.factories.RelationshipFactory;
import com.dotmarketing.portlets.structure.model.ContentletRelationships;
import com.dotmarketing.portlets.structure.model.ContentletRelationships.ContentletRelationshipRecords;
import com.dotmarketing.portlets.structure.model.Field;
import com.dotmarketing.portlets.structure.model.Relationship;
import com.dotmarketing.portlets.structure.model.Structure;
import com.dotmarketing.services.ContentletMapServices;
import com.dotmarketing.services.ContentletServices;
import com.dotmarketing.services.PageServices;
import com.dotmarketing.tag.business.TagAPI;
import com.dotmarketing.util.AdminLogger;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.ConfigUtils;
import com.dotmarketing.util.DateUtil;
import com.dotmarketing.util.InodeUtils;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.LuceneHits;
import com.dotmarketing.util.PaginatedArrayList;
import com.dotmarketing.util.RegEX;
import com.dotmarketing.util.RegExMatch;
import com.dotmarketing.util.UUIDGenerator;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.lucene.LuceneUtils;
import com.liferay.portal.NoSuchUserException;
import com.liferay.portal.model.User;
import com.liferay.util.FileUtil;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * @author Jason Tesser
 * @author David Torres
 * @since 1.5
 *
 */
public class ContentletAPIImpl implements ContentletAPI {

	private static final String CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT = "Can't change state of checked out content or where inode is not set. Use Search or Find then use method";
	private ContentletFactory conFac;
	private PermissionAPI perAPI;
	private CategoryAPI catAPI;
	private RelationshipAPI relAPI;
	private FieldAPI fAPI;
	private LanguageAPI lanAPI;
	private TagAPI tagAPI;
	private DistributedJournalAPI<String> distAPI;
	private int MAX_LIMIT = 100000;

	private static final String backupPath = ConfigUtils.getBackupPath() + java.io.File.separator + "contentlets";

	public ContentletAPIImpl () {
		fAPI = APILocator.getFieldAPI();
		conFac = FactoryLocator.getContentletFactory();
		perAPI = APILocator.getPermissionAPI();
		catAPI = APILocator.getCategoryAPI();
		relAPI = APILocator.getRelationshipAPI();
		lanAPI = APILocator.getLanguageAPI();
		distAPI = APILocator.getDistributedJournalAPI();
		tagAPI = APILocator.getTagAPI();
	}

	public List<Contentlet> findAllContent(int offset, int limit) throws DotDataException{
		return conFac.findAllCurrent(offset, limit);
	}

	public boolean isContentlet(String inode) throws DotDataException, DotRuntimeException {
		Contentlet contentlet = new Contentlet();
		try{
			contentlet = find(inode, APILocator.getUserAPI().getSystemUser(), true);
		}catch (DotSecurityException dse) {
			throw new DotRuntimeException("Unable to use system user : ", dse);
		}catch (Exception e) {
			Logger.debug(this,"Inode unable to load as contentlet.  Asssuming it is not content");
			return false;
		}
		if(contentlet!=null){
			if(InodeUtils.isSet(contentlet.getInode())){
			return true;
			}
		}
		return false;
	}

	/*public Contentlet find(String inode, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException {
		String id = "";
		try{
			id = inode;
		}catch(Exception e){
			throw new DotDataException("Inode must be a String", e);
		}
		return find(id, user, respectFrontendRoles);
	}*/

	public Contentlet find(String inode, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException {
		Contentlet c = conFac.find(inode);
		if(c  == null)
			return null;
		if(perAPI.doesUserHavePermission(c, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			return c;
		}else{
			throw new DotSecurityException("User does not have permissions to Contentlet");
		}
	}

	public List<Contentlet> findByStructure(String structureInode, User user,	boolean respectFrontendRoles, int limit, int offset) throws DotDataException,DotSecurityException {
		List<Contentlet> contentlets = conFac.findByStructure(structureInode, limit, offset);
		return perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
	}

	public List<Contentlet> findByStructure(Structure structure, User user,	boolean respectFrontendRoles, int limit, int offset) throws DotDataException,DotSecurityException {
		return findByStructure(structure.getInode(), user, respectFrontendRoles, limit, offset);
	}

	/**
	 * Returns a live Contentlet Object for a given language
	 * @param languageId
	 * @param inode
	 * @return Contentlet
	 * @throws DotDataException
	 */
	public Contentlet findContentletForLanguage(long languageId,	Identifier contentletId) throws DotDataException {
		Contentlet con = conFac.findContentletForLanguage(languageId, contentletId);
		if(con == null){
			Logger.debug(this,"No working contentlet found for language");
		}
		return con;
	}

	public Contentlet findContentletByIdentifier(String identifier, boolean live, long languageId, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException, DotContentletStateException {
		Long languageIdLong = languageId <= 0?null:new Long(languageId);
		Contentlet con = null;
		try {
			StringBuilder sb = new StringBuilder();
			sb.append("+type:content +identifier:" + identifier);

			if(live){
				sb.append(" +live:true ");
			}else{
				sb.append(" +working:true ");
			}



			if(languageIdLong != null && languageIdLong > 0){
				sb.append(" +languageId:" + languageIdLong);
			}

			List <ContentletSearch> l = this.searchIndex(sb.toString(), 1, 0, null, user, respectFrontendRoles);
			con = conFac.find(l.get(0).getInode());
		} catch (Exception e) {
			Logger.debug(this.getClass(), "Can't find identifier: " + identifier + " in the Luceene Index, falling back to db.  Maybe this is an inode and not an identifier?");
		}

		// if we can't get from factory(cache), go to db
		if(con == null){
			con = conFac.findContentletByIdentifier(identifier, live, languageIdLong);
		}
		if(con == null){
			throw new DotContentletStateException("No contenlet found for given identifier");
		}
		if(perAPI.doesUserHavePermission(con, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			return con;
		}else{
			throw new DotSecurityException("User has no permission to use/read contentlet");
		}
	}

	public List<Contentlet> findContentletsByIdentifiers(String[] identifiers, boolean live, long languageId, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException, DotContentletStateException {
		List<Contentlet> l = new ArrayList<Contentlet>();
		Long languageIdLong = languageId <= 0?null:new Long(languageId);
		for(String identifier : identifiers){
			Contentlet con = findContentletByIdentifier(identifier.trim(), live, languageIdLong, user, respectFrontendRoles);
			l.add(con);
		}
		return l;
	}

	public List<Contentlet> findContentlets(List<String> inodes)throws DotDataException {
		return conFac.findContentlets(inodes);
	}


	public List<Contentlet> findContentletsByFolder(Folder parentFolder, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException {

		try {
			return perAPI.filterCollection(search("+conFolder:" + parentFolder.getInode(), -1, 0, null , user, respectFrontendRoles), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
		} catch (ParseException e) {
			Logger.error(ContentletAPIImpl.class, e.getMessage(), e);
			throw new DotRuntimeException(e.getMessage(), e);
		}

	}

	public List<Contentlet> findContentletsByHost(Host parentHost, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException {
		try {
			return perAPI.filterCollection(search("+conHost:" + parentHost.getIdentifier() + " +working:true", -1, 0, null , user, respectFrontendRoles), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
		} catch (ParseException e) {
			Logger.error(ContentletAPIImpl.class, e.getMessage(), e);
			throw new DotRuntimeException(e.getMessage(), e);
		}
	}

	public void publish(Contentlet contentlet, User user, boolean respectFrontendRoles) throws DotSecurityException, DotDataException, DotContentletStateException, DotStateException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_PUBLISH, user, respectFrontendRoles)){
			Logger.debug(PublishFactory.class, "publishAsset: user = " + user.getEmailAddress() + ", don't have permissions to publish: " + contentlet.getInode());

			//If the contentlet has CMS Owner Publish permission on it, the user creating the new contentlet is allowed to publish

			List<Role> roles = perAPI.getRoles(contentlet.getPermissionId(), PermissionAPI.PERMISSION_PUBLISH, "CMS Owner", 0, -1);
			Role cmsOwner = APILocator.getRoleAPI().loadCMSOwnerRole();
			boolean isCMSOwner = false;
			if(roles.size() > 0){
				for (Role role : roles) {
					if(role == cmsOwner){
						isCMSOwner = true;
						break;
					}
				}
				if(!isCMSOwner){
					throw new DotSecurityException("User does not have permission to publish contentlet with inode " + contentlet.getInode());
				}
			}else{
				throw new DotSecurityException("User does not have permission to publish contentlet with inode " + contentlet.getInode());
			}
		}
		String syncMe = (UtilMethods.isSet(contentlet.getIdentifier()))  ? contentlet.getIdentifier() : UUIDGenerator.generateUuid()  ;

		synchronized (syncMe) {


			Logger.debug(this, "*****I'm a Contentlet -- Publishing");

			Contentlet workingCon = findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId(), user, respectFrontendRoles);

			if (workingCon == null || !InodeUtils.isSet(workingCon.getInode())) {
				workingCon = contentlet;
			}

			if (InodeUtils.isSet(contentlet.getIdentifier())) {
				// Unpublish all other live - We should recover if somehow there is
				// bad data and multiple versions of a contentlet end up being live
				// unpublish(contentlet, user);
				setLiveContentOff(contentlet);

			}

			//Set contentlet to live and unlocked
			contentlet.setLive(true);
			contentlet.setLocked(false);

			conFac.save(contentlet);

			finishPublish(contentlet, false);

		}
	}

	private void setLiveContentOff(Contentlet contentlet) throws DotDataException {
		List<Contentlet> liveCons = new ArrayList<Contentlet>();
		if (InodeUtils.isSet(contentlet.getIdentifier())) {
			liveCons = conFac.findContentletsByIdentifier(contentlet.getIdentifier(), true, contentlet
					.getLanguageId());
		}
		Logger.debug(this, "working contentlet =" + contentlet.getInode());
		for (Contentlet liveCon : liveCons) {
			if ((liveCon != null) && (InodeUtils.isSet(liveCon.getInode()))
					&& (!liveCon.getInode().equalsIgnoreCase(contentlet.getInode()))) {

				Logger.debug(this, "live contentlet =" + liveCon.getInode());
				// sets previous live to false
				liveCon.setLive(false);
				liveCon.setModDate(new java.util.Date());

				// persists it
				conFac.save(liveCon);
			}
		}
	}


	private void finishPublish(Contentlet contentlet, boolean isNew) throws DotSecurityException, DotDataException,
	DotContentletStateException, DotStateException {
		finishPublish(contentlet, isNew, true);
	}


	private void finishPublish(Contentlet contentlet, boolean isNew, boolean isNewVersion) throws DotSecurityException, DotDataException,
			DotContentletStateException, DotStateException {
		if (!contentlet.isWorking())
			throw new DotContentletStateException("Only the working version can be published");

		User user = APILocator.getUserAPI().getSystemUser();

		// DOTCMS - 4393
		// Publishes the files associated with the Contentlet
		List<Field> fields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());
		for (Field field : fields) {
			if (field.getFieldType().equals(Field.FieldType.IMAGE.toString())
					|| field.getFieldType().equals(Field.FieldType.FILE.toString())) {

				try {
					String value = "";
					if(UtilMethods.isSet(getFieldValue(contentlet, field))){
						value = getFieldValue(contentlet, field).toString();
					}
					Identifier id = (Identifier) InodeFactory.getInode(value, Identifier.class);
					if (InodeUtils.isSet(id.getInode())) {
						File file = (File) IdentifierFactory.getWorkingChildOfClass(id, File.class);
						PublishFactory.publishAsset(file, user, false, isNewVersion);
					}
				} catch (Exception ex) {
					Logger.debug(this, ex.toString());
					throw new DotStateException("Problem occured while publishing file");
				}
			}
		}

		// gets all not live file children
		List<File> files = getRelatedFiles(contentlet, user, false);
		for (File file : files) {
			Logger.debug(this, "*****I'm a Contentlet -- Publishing my File Child=" + file.getInode());
			try {
				PublishFactory.publishAsset(file, user, false, isNewVersion);
			} catch (DotSecurityException e) {
				Logger.debug(this, "User has permissions to publish the content = " + contentlet.getIdentifier()
						+ " but not the related file = " + file.getIdentifier());
			} catch (Exception e) {
				throw new DotStateException("Problem occured while publishing file");
			}
		}

		// gets all not live link children
		Logger.debug(this, "IM HERE BEFORE PUBLISHING LINKS FOR A CONTENTLET!!!!!!!");
		List<Link> links = getRelatedLinks(contentlet, user, false);
		for (Link link : links) {
			Logger.debug(this, "*****I'm a Contentlet -- Publishing my Link Child=" + link.getInode());
			try {
				PublishFactory.publishAsset(link, user, false, isNewVersion);
			} catch (DotSecurityException e) {
				Logger.debug(this, "User has permissions to publish the content = " + contentlet.getIdentifier()
						+ " but not the related link = " + link.getIdentifier());
				throw new DotStateException("Problem occured while publishing link");
			} catch (Exception e) {
				throw new DotStateException("Problem occured while publishing file");
			}
		}

		// writes the contentlet object to a file
		APILocator.getDistributedJournalAPI().addContentIndexEntry(contentlet);

		if (!isNew) {
			// writes the contentlet to a live directory under velocity folder
			ContentletServices.invalidate(contentlet);
			ContentletMapServices.invalidate(contentlet);

			CacheLocator.getContentletCache().remove(String.valueOf(contentlet.getInode()));

			// Need to refresh the live pages that reference this piece of
			// content
			publishRelatedHtmlPages(contentlet);
		}

	}


	public List<Contentlet> search(String luceneQuery, int limit, int offset,String sortBy, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, ParseException  {
		return search(luceneQuery, limit, offset, sortBy, user, respectFrontendRoles, PermissionAPI.PERMISSION_READ);
	}

	public List<Contentlet> search(String luceneQuery, int limit, int offset, String sortBy, User user, boolean respectFrontendRoles, int requiredPermission) throws DotDataException,DotSecurityException, ParseException {
		PaginatedArrayList<Contentlet> contents = new PaginatedArrayList<Contentlet>();
		ArrayList<String> inodes = new ArrayList<String>();


		PaginatedArrayList <ContentletSearch> list =(PaginatedArrayList)searchIndex(luceneQuery, limit, offset, sortBy, user, respectFrontendRoles);
		contents.setTotalResults(list.getTotalResults());
		for(ContentletSearch conwrap: list){

			inodes.add(conwrap.getInode());
		}


		List<Contentlet> contentlets = findContentlets(inodes);
		Map<String, Contentlet> map = new HashMap<String, Contentlet>(contentlets.size());
		for (Contentlet contentlet : contentlets) {
			map.put(contentlet.getInode(), contentlet);
		}
		for (String inode : inodes) {
			if(map.get(inode) != null)
				contents.add(map.get(inode));
		}
		return contents;

	}

	public List<Contentlet> searchByIdentifier(String luceneQuery, int limit, int offset,String sortBy, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, ParseException  {
		return searchByIdentifier(luceneQuery, limit, offset, sortBy, user, respectFrontendRoles, PermissionAPI.PERMISSION_READ);
	}

	public List<Contentlet> searchByIdentifier(String luceneQuery, int limit, int offset, String sortBy, User user, boolean respectFrontendRoles, int requiredPermission) throws DotDataException,DotSecurityException, ParseException {
		PaginatedArrayList<Contentlet> contents = new PaginatedArrayList<Contentlet>();
		PaginatedArrayList <ContentletSearch> list =(PaginatedArrayList)searchIndex(luceneQuery, limit, offset, sortBy, user, respectFrontendRoles);
		contents.setTotalResults(list.getTotalResults());
		String[] identifiers = new String[list.size()];
		int count = 0;
		for(ContentletSearch conwrap: list){
			identifiers[count] = conwrap.getIdentifier();
		    count++;
		}

		List<Contentlet> contentlets = findContentletsByIdentifiers(identifiers, false, APILocator.getLanguageAPI().getDefaultLanguage().getId(), user, respectFrontendRoles);
		Map<String, Contentlet> map = new HashMap<String, Contentlet>(contentlets.size());
		for (Contentlet contentlet : contentlets) {
			map.put(contentlet.getIdentifier(), contentlet);
		}
		for (String identifier : identifiers) {
			if(map.get(identifier) != null && !contents.contains(map.get(identifier))){
				contents.add(map.get(identifier));
			}
		}
		return contents;

	}

	public List <ContentletSearch> searchIndex(String luceneQuery, int limit, int offset, String sortBy, User user, boolean respectFrontendRoles)throws ParseException, DotSecurityException, DotDataException {
		boolean isAdmin = false;
		List<Role> roles = new ArrayList<Role>();
		if(user == null && !respectFrontendRoles){
			throw new DotSecurityException("You must specify a user if you are not respecting frontend roles");
		}
		if(user != null){
			if (!APILocator.getRoleAPI().doesUserHaveRole(user, APILocator.getRoleAPI().loadCMSAdminRole())) {
				roles = APILocator.getRoleAPI().loadRolesForUser(user.getUserId());
			}else{
				isAdmin = true;
			}
		}
		StringBuffer buffy = new StringBuffer(luceneQuery);

		// Permissions in the query
		if (!isAdmin) {
			if(user != null)
				buffy.append(" +((+owner:" + user.getUserId() + " +ownerCanRead:true) ");
			else
				buffy.append(" +(");
			if (0 < roles.size()) {
				buffy.append(" (");
				for (Role role : roles) {
					buffy.append("permissions:P" + role.getId() + ".1P* ");
				}
				buffy.append(") ");
			}
			if(respectFrontendRoles) {
				buffy.append("(permissions:P" + APILocator.getRoleAPI().loadCMSAnonymousRole().getId() + ".1P*) ");
				if(user != null)
					buffy.append("(permissions:P" + APILocator.getRoleAPI().loadLoggedinSiteRole().getId() + ".1P*)");
			}
			buffy.append(")");
		}

		int originalLimit = limit;
		if(UtilMethods.isSet(sortBy) && sortBy.trim().equalsIgnoreCase("random")){
			sortBy="random";
			if(limit>=(MAX_LIMIT -10)){
				limit += MAX_LIMIT;
			}else{
				limit = MAX_LIMIT;
			}
		}
		LuceneHits lc = conFac.indexSearch(buffy.toString(), limit, offset, sortBy);
		lc.setLuceneQuery(luceneQuery);
		PaginatedArrayList <ContentletSearch> list=new PaginatedArrayList<ContentletSearch>();
		list.setTotalResults(lc.getTotal());

		for (int i = 0; i < lc.length(); i++) {
			if(i==originalLimit && originalLimit > 0){
				break;
			}
			try{

				Map<String, Object> hm = new HashMap<String, Object>();
				ContentletSearch conwrapper= new ContentletSearch();
				conwrapper.setIdentifier(lc.doc(i).get("identifier"));
				conwrapper.setInode(lc.doc(i).get("inode"));
				list.add(conwrapper);

			}
			catch(Exception e){
				Logger.error(this,e.getMessage(),e);
			}

		}
		return list;
	}

	public void publishRelatedHtmlPages(Contentlet contentlet){
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		//Get the contentlet Identifier to gather the related pages
		Identifier identifier = (Identifier) InodeFactory.getInode(contentlet.getIdentifier(),Identifier.class);
		//Get the identifier's number of the related pages
		List<MultiTree> multitrees = (List<MultiTree>) MultiTreeFactory.getMultiTreeByChild(identifier.getInode());
		for(MultiTree multitree : multitrees)
		{
			//Get the Identifiers of the related pages
			Identifier htmlPageIdentifier = (Identifier) InodeFactory.getInode(multitree.getParent1(),Identifier.class);
			//Get the pages
			List<HTMLPage> pages = HTMLPageFactory.getChildrenHTMLPageByOrder(htmlPageIdentifier);
			for(HTMLPage page : pages)
			{
				if(page.isLive())
				{
					//Rebuild the pages' files
					PageServices.invalidate(page);
				}
			}
		}
	}

	public void cleanHostField(Structure structure, User user, boolean respectFrontendRoles)
		throws DotSecurityException, DotDataException {

		if(!perAPI.doesUserHavePermission(structure, PermissionAPI.PERMISSION_PUBLISH, user, respectFrontendRoles)){
			throw new DotSecurityException("Must be able to publish structure to clean all the fields");
		}

		conFac.cleanHostField(structure.getInode());
		conFac.cleanIdentifierHostField(structure.getInode());

	}

	public void cleanField(Structure structure, Field field, User user,	boolean respectFrontendRoles) throws DotSecurityException, DotDataException {

		if(!perAPI.doesUserHavePermission(structure, PermissionAPI.PERMISSION_PUBLISH, user, respectFrontendRoles)){
			throw new DotSecurityException("Must be able to publish structure to clean all the fields");
		}

		String type = field.getFieldType();
		if(Field.FieldType.LINE_DIVIDER.toString().equals(type) ||
				Field.FieldType.TAB_DIVIDER.toString().equals(type) ||
				Field.FieldType.RELATIONSHIPS_TAB.toString().equals(type) ||
				Field.FieldType.CATEGORIES_TAB.toString().equals(type) ||
				Field.FieldType.PERMISSIONS_TAB.toString().equals(type))
		{
			throw new DotDataException("Unable to clean a " + type + " system field");
		}

		//http://jira.dotmarketing.net/browse/DOTCMS-2178
		if(Field.FieldType.BINARY.toString().equals(field.getFieldType())){
			List<Contentlet> contentlets = conFac.findByStructure(structure.getInode(),0,0);

			deleteBinaryFiles(contentlets,field);

			return; // Binary fields have nothing to do with database.
		}

		conFac.cleanField(structure.getInode(), field);

	}

	public Date getNextReview(Contentlet content, User user, boolean respectFrontendRoles) throws DotSecurityException {

		Date baseDate = new Date();
		String reviewInterval = content.getReviewInterval();
		Pattern p = Pattern.compile("(\\d+)([dmy])");
		Matcher m = p.matcher(reviewInterval);
		boolean b = m.matches();
		GregorianCalendar cal = new GregorianCalendar();
		cal.setTime(baseDate);
		if (b) {
			int num = Integer.parseInt(m.group(1));
			String qual = m.group(2);
			if (qual.equals("d")) {
				cal.add(GregorianCalendar.DATE, num);
			}
			if (qual.equals("m")) {
				cal.add(GregorianCalendar.MONTH, num);
			}
			if (qual.equals("y")) {
				cal.add(GregorianCalendar.YEAR, num);
			}
		}
		return cal.getTime();
	}

	public List<Map<String, Object>> getContentletReferences(Contentlet contentlet, User user, boolean respectFrontendRoles) throws DotSecurityException, DotDataException, DotContentletStateException {
		List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
		if(contentlet == null || !InodeUtils.isSet(contentlet.getInode())){
			throw new DotContentletStateException("Contentlet must exist");
		}
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet");
		}
		Identifier id = IdentifierCache.getIdentifierFromIdentifierCache(contentlet);
		if (!InodeUtils.isSet(id.getInode()))
			return results;
		List<MultiTree> trees = MultiTreeFactory.getMultiTreeByChild(id.getInode());
		for (MultiTree tree : trees) {
			HTMLPage page = (HTMLPage) IdentifierFactory.getWorkingChildOfClass(InodeFactory.getInode(tree.getParent1(), Identifier.class),
					HTMLPage.class);
			Container container = (Container) IdentifierFactory.getWorkingChildOfClass(InodeFactory.getInode(tree.getParent2(), Identifier.class),
					Container.class);
			if (InodeUtils.isSet(page.getInode()) && InodeUtils.isSet(container.getInode())) {
				Map<String, Object> map = new HashMap<String, Object>();
				map.put("page", page);
				map.put("container", container);
				results.add(map);
			}
		}
		return results;
	}

	public Object getFieldValue(Contentlet contentlet, Field theField){
		try {

			if(fAPI.isElementConstant(theField)){
				if(contentlet.getMap().get(theField.getVelocityVarName())==null)
				    contentlet.getMap().put(theField.getVelocityVarName(), theField.getValues());
				return theField.getValues();
			}

			if(theField.getFieldType().equals(Field.FieldType.HOST_OR_FOLDER.toString())){
				if(FolderAPI.SYSTEM_FOLDER_ID.equals(contentlet.getFolder()))
					 return contentlet.getHost();
				else
				     return contentlet.getFolder();
			}else if(theField.getFieldType().equals(Field.FieldType.CATEGORY.toString())){
				Category category = catAPI.find(theField.getValues(), APILocator.getUserAPI().getSystemUser(), false);
				// Get all the Contentlets Categories
				List<Category> selectedCategories = catAPI.getParents(contentlet, APILocator.getUserAPI().getSystemUser(), false);
				Set<Category> categoryList = new HashSet<Category>();
				List<Category> categoryTree = catAPI.getAllChildren(category, APILocator.getUserAPI().getSystemUser(), false);
				if (selectedCategories.size() > 0 && categoryTree != null) {
					for (int k = 0; k < categoryTree.size(); k++) {
						Category cat = (Category) categoryTree.get(k);
						for (Category categ : selectedCategories) {
							if (categ.getInode().equalsIgnoreCase(cat.getInode())) {
								categoryList.add(cat);
							}
						}
					}
				}
				return categoryList;
			}else{
				return contentlet.getMap().get(theField.getVelocityVarName());
			}
		} catch (Exception e) {
			Logger.error(this, e.getMessage(), e);
			return null;
		}
	}

	public void addLinkToContentlet(Contentlet contentlet, String linkInode, String relationName, User user, boolean respectFrontendRoles)throws DotSecurityException, DotDataException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if (InodeUtils.isSet(linkInode)) {
			Link link = (Link) InodeFactory.getInode(linkInode, Link.class);
			Identifier identifier = IdentifierCache.getIdentifierFromIdentifierCache(link);
			relAPI.addRelationship(contentlet.getInode(),identifier.getInode(), relationName);
			ContentletServices.invalidate(contentlet, true);
			// writes the contentlet object to a file
			ContentletMapServices.invalidate(contentlet, true);
		}
	}

	public void addFileToContentlet(Contentlet contentlet, String fileInode, String relationName, User user, boolean respectFrontendRoles)throws DotSecurityException, DotDataException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if (InodeUtils.isSet(fileInode)) {
			File file = (File) InodeFactory.getInode(fileInode, File.class);
			Identifier identifier = IdentifierCache.getIdentifierFromIdentifierCache(file);
			relAPI.addRelationship(contentlet.getInode(),identifier.getInode(), relationName);
			ContentletServices.invalidate(contentlet, true);
			// writes the contentlet object to a file
			ContentletMapServices.invalidate(contentlet, true);
		}
	}

	public void addImageToContentlet(Contentlet contentlet, String imageInode, String relationName, User user, boolean respectFrontendRoles)throws DotSecurityException, DotDataException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if (InodeUtils.isSet(imageInode)) {
			File image = (File) InodeFactory.getInode(imageInode, File.class);
			Identifier identifier = IdentifierCache.getIdentifierFromIdentifierCache(image);
			relAPI.addRelationship(contentlet.getInode(),identifier.getInode(), relationName);
			ContentletServices.invalidate(contentlet, true);
			// writes the contentlet object to a file
			ContentletMapServices.invalidate(contentlet, true);
		}
	}

	public List<Contentlet> findPageContentlets(String HTMLPageIdentifier,String containerIdentifier, String orderby, boolean working, long languageId, User user, boolean respectFrontendRoles)	throws DotSecurityException, DotDataException {
		List<Contentlet> contentlets = conFac.findPageContentlets(HTMLPageIdentifier, containerIdentifier, orderby, working, languageId);
		return perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
	}

	public ContentletRelationships getAllRelationships(String contentletInode, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException {

		return getAllRelationships(find(contentletInode, user, respectFrontendRoles));
	}

	public ContentletRelationships getAllRelationships(Contentlet contentlet)throws DotDataException {

		ContentletRelationships cRelationships = new ContentletRelationships(contentlet);
		Structure structure = contentlet.getStructure();
		List<ContentletRelationshipRecords> matches = cRelationships.getRelationshipsRecords();
		List<Relationship> relationships = RelationshipFactory.getAllRelationshipsByStructure(structure);

		for (Relationship relationship : relationships) {

			ContentletRelationshipRecords records = null;
			List<Contentlet> contentletList = null;

			if (RelationshipFactory.isSameStructureRelationship(relationship, structure)) {

				//If it's a same structure kind of relationship we need to pull all related content
				//on both roles as parent and a child of the relationship

				//Pulling as child
				records = cRelationships.new ContentletRelationshipRecords(relationship, false);
				contentletList = new ArrayList<Contentlet> ();
				try {
					contentletList.addAll(getRelatedContent(contentlet, relationship, false, APILocator.getUserAPI().getSystemUser(), true));
				} catch (DotSecurityException e) {
					Logger.error(this,"Unable to get system user",e);
				}
				records.setRecords(contentletList);
				matches.add(records);

				//Pulling as parent
				records = cRelationships.new ContentletRelationshipRecords(relationship, true);
				contentletList = new ArrayList<Contentlet> ();
				try {
					contentletList.addAll(getRelatedContent(contentlet, relationship, true, APILocator.getUserAPI().getSystemUser(), true));
				} catch (DotSecurityException e) {
					Logger.error(this,"Unable to get system user",e);
				}
				records.setRecords(contentletList);
				matches.add(records);

			} else
			if (RelationshipFactory.isChildOfTheRelationship(relationship, structure)) {

				records = cRelationships.new ContentletRelationshipRecords(relationship, false);
				try{
					contentletList = getRelatedContent(contentlet, relationship, APILocator.getUserAPI().getSystemUser(), true);
				} catch (DotSecurityException e) {
					Logger.error(this,"Unable to get system user",e);
				}
				records.setRecords(contentletList);
				matches.add(records);

			} else
			if (RelationshipFactory.isParentOfTheRelationship(relationship, structure)) {
				records = cRelationships.new ContentletRelationshipRecords(relationship, true);
				try{
					contentletList = getRelatedContent(contentlet, relationship, APILocator.getUserAPI().getSystemUser(), true);
				} catch (DotSecurityException e) {
					Logger.error(this,"Unable to get system user",e);
				}
				records.setRecords(contentletList);
				matches.add(records);
			}



		}

		return cRelationships;
	}

	public List<Contentlet> getAllLanguages(Contentlet contentlet, Boolean isLiveContent, User user, boolean respectFrontendRoles)
		throws DotDataException, DotSecurityException {

		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet");
		}

		List<Contentlet> contentletList =  null;


		if(isLiveContent != null){
			contentletList = conFac.getContentletsByIdentifier(contentlet.getIdentifier(), isLiveContent);
		}else{
			contentletList = conFac.getContentletsByIdentifier(contentlet.getIdentifier(), null);
		}
		return contentletList;
	}

	public void unlock(Contentlet contentlet, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);

		if(contentlet == null){
			throw new DotContentletStateException("The contentlet cannot Be null");
		}

		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_WRITE, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot edit Contentlet");
		}

		if(!UtilMethods.isSet(user))
		{
			user = APILocator.getUserAPI().getSystemUser();
		}

		if(!UtilMethods.isSet(user))
		{
			throw new DotSecurityException("Must specificy user who is locking the contentlet");
		}

		// persists the webasset
		conFac.unlock(contentlet.getInode(), user);
		distAPI.addContentIndexEntry(contentlet);
	}

	public Identifier getRelatedIdentifier(Contentlet contentlet,String relationshipType, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException {
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet");
		}
		return conFac.getRelatedIdentifier(contentlet, relationshipType);
	}

	public List<File> getRelatedFiles(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		return perAPI.filterCollection(conFac.getRelatedFiles(contentlet), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
	}

	public List<Link> getRelatedLinks(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		return perAPI.filterCollection(conFac.getRelatedLinks(contentlet), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
	}

	public List<Contentlet> getRelatedContent(Contentlet contentlet,Relationship rel, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException {

		boolean isSameStructRelationship = rel.getParentStructureInode().equalsIgnoreCase(rel.getChildStructureInode());
		String q = "";

		if(isSameStructRelationship) {
			q = "+type:content +(" + rel.getRelationTypeValue() + "-parent:" + contentlet.getIdentifier() + " " +
				rel.getRelationTypeValue() + "-child:" + contentlet.getIdentifier() + ") ";
			if(!InodeUtils.isSet(contentlet.getIdentifier())){
				q = "+type:content +(" + rel.getRelationTypeValue() + "-parent:" + "0 " +
				rel.getRelationTypeValue() + "-child:"  + "0 ) ";
			}
		} else {
			q = "+type:content +" + rel.getRelationTypeValue() + ":" + contentlet.getIdentifier();
			if(!InodeUtils.isSet(contentlet.getIdentifier())){
				q = "+type:content +" + rel.getRelationTypeValue() + ":" + "0";
			}
		}

		try{
			return perAPI.filterCollection(searchByIdentifier(q, -1, 0, rel.getRelationTypeValue() + "-" + contentlet.getIdentifier() + "-order" , user, respectFrontendRoles), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
		}catch (ParseException e) {
			throw new DotDataException("Unable look up related content",e);
		}

	}

	public List<Contentlet> getRelatedContent(Contentlet contentlet,Relationship rel, boolean pullByParent, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException {

		boolean isSameStructureRelationship = rel.getParentStructureInode().equalsIgnoreCase(rel.getChildStructureInode());
		String q = "";

		if(isSameStructureRelationship) {
			String disc = pullByParent?"-parent":"-child";
			q = "+type:content +" + rel.getRelationTypeValue() + disc + ":" + contentlet.getIdentifier();
			if(!InodeUtils.isSet(contentlet.getIdentifier()))
				q = "+type:content +" + rel.getRelationTypeValue() + disc + ":" + "0";

		} else {
			q = "+type:content +" + rel.getRelationTypeValue() + ":" + contentlet.getIdentifier();
			if(!InodeUtils.isSet(contentlet.getIdentifier()))
				q = "+type:content +" + rel.getRelationTypeValue() + ":" + "0";
		}

		try{
			return perAPI.filterCollection(searchByIdentifier(q, -1, 0, rel.getRelationTypeValue() + "-" + contentlet.getIdentifier() + "-order" , user, respectFrontendRoles), PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
		}catch (ParseException e) {
			throw new DotDataException("Unable look up related content",e);
		}

	}

	public void delete(Contentlet contentlet, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		List<Contentlet> contentlets = new ArrayList<Contentlet>();
		contentlets.add(contentlet);
		delete(contentlets, user, respectFrontendRoles);
	}

	public void delete(Contentlet contentlet, User user,boolean respectFrontendRoles, boolean allVersions) throws DotDataException,DotSecurityException {
		List<Contentlet> contentlets = new ArrayList<Contentlet>();
		contentlets.add(contentlet);
		delete(contentlets, user, respectFrontendRoles, allVersions);
	}

	public void delete(List<Contentlet> contentlets, User user,	boolean respectFrontendRoles) throws DotDataException, DotSecurityException {
		if(contentlets == null || contentlets.size() == 0){
			Logger.info(this, "No contents passed to delete so returning");
			return;
		}
		for (Contentlet contentlet : contentlets)
			if(contentlet.getInode().equals(""))
				throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);

		List<Contentlet> perCons = perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_PUBLISH, respectFrontendRoles, user);
		List<Contentlet> contentletsVersion = new ArrayList<Contentlet>();
		contentletsVersion.addAll(contentlets);

		if(perCons.size() != contentlets.size()){
			throw new DotSecurityException("User does not have permission to delete some or all of the contentlets");
		}

		List<String> l = new ArrayList<String>();

		for (Contentlet contentlet : contentlets) {
			if(!l.contains(contentlet)){
				l.add(contentlet.getIdentifier());
			}
		}

		AdminLogger.log(ContentletAPIImpl.class, "delete", "User trying to delete the following contents" + l.toString(), user);

		for (Contentlet con : perCons) {
			List<Contentlet> otherLanguageCons;
			otherLanguageCons = conFac.getContentletsByIdentifier(con.getIdentifier());
			boolean cannotDelete = false;
			for (Contentlet contentlet : otherLanguageCons) {
				if(contentlet.getInode() != contentlet.getInode() && contentlet.getLanguageId() != con.getLanguageId() && !contentlet.isArchived()){
					cannotDelete = true;
					distAPI.addContentIndexEntryToDelete(contentlet.getIdentifier());
					break;
				}
			}
			if(cannotDelete){
				Logger.warn(this, "Cannot delete content that has a working copy in another language");
				perCons.remove(con);
				continue;
			}
			catAPI.removeChildren(con, APILocator.getUserAPI().getSystemUser(), true);
			catAPI.removeParents(con, APILocator.getUserAPI().getSystemUser(), true);
			List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(con.getStructure());
			for(Relationship relationship :  rels){
				deleteRelatedContent(con,relationship,user,respectFrontendRoles);
			}

			contentletsVersion.addAll(findAllVersions(IdentifierCache.getIdentifierFromIdentifierCache(con.getIdentifier()), user, respectFrontendRoles));
			List<MultiTree> mts = MultiTreeFactory.getMultiTreeByChild(con.getIdentifier());
			for (MultiTree mt : mts) {
				Identifier pageIdent = IdentifierCache.getIdentifierFromIdentifierCache(mt.getParent1());
				if(pageIdent != null && UtilMethods.isSet(pageIdent.getInode())){
					PageServices.invalidate(APILocator.getHTMLPageAPI().loadPageByPath(pageIdent.getURI(), pageIdent.getHostId()));
				}
				MultiTreeFactory.deleteMultiTree(mt);
			}
		}

		// jira.dotmarketing.net/browse/DOTCMS-1073
		if (perCons.size() > 0) {
			XStream _xstream = new XStream(new DomDriver());
			Date date = new Date();
			SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss");
			String lastmoddate = sdf.format(date);
			java.io.File _writing = null;
			java.io.File _writingwbin = null;

			java.io.File backupFolder = new java.io.File(backupPath);
			if (!backupFolder.exists()) {
				backupFolder.mkdirs();
			}
			for(Contentlet cont:perCons){
				Structure st=cont.getStructure();
				List <Field> fields= st.getFields();
			    List<Map<String,Object>> filelist = new ArrayList<Map<String,Object>>();
			    ContentletAndBinary contentwbin= new ContentletAndBinary ();
			    contentwbin.setMap(cont.getMap()) ;
			    Boolean arebinfiles=false;
			    java.io.File file=null;
				for(Field field:fields){
					if(field.getFieldType().equals(Field.FieldType.BINARY.toString())){
						try{
							file = getBinaryFile(cont.getInode(), field.getVelocityVarName(), user);
						}catch (Exception ex) {
							Logger.debug(this, ex.getMessage(), ex);
						}
						if (file != null) {
							byte[] bytes = null;
							try {
								bytes = FileUtil.getBytes(file);
							} catch (IOException e) {
							}
							Map<String,Object> temp = new HashMap<String,Object>();
							temp.put(file.getName(), bytes);
							filelist.add(temp);
							arebinfiles = true;
						}
					}
				}

				_writing = new java.io.File(backupPath + java.io.File.separator + cont.getIdentifier().toString() + ".xml");
				_writingwbin = new java.io.File(backupPath + java.io.File.separator + cont.getIdentifier().toString() + "_bin" + ".xml");
				BufferedOutputStream _bout = null;

				if(!arebinfiles){
					try {
						_bout = new BufferedOutputStream(new FileOutputStream(_writing));
					} catch (FileNotFoundException e) {
					}
					_xstream.toXML(cont, _bout);
				}
				else{
					try {
						_bout = new BufferedOutputStream(new FileOutputStream(_writingwbin));
					} catch (FileNotFoundException e) {

					}
					contentwbin.setBinaryFilesList(filelist);
					_xstream.toXML(contentwbin, _bout);
					arebinfiles=false;
				}
			}

		}

		conFac.delete(contentletsVersion);


		for (Contentlet contentlet : perCons) {
			distAPI.addContentIndexEntryToDelete(contentlet.getIdentifier());
			IdentifierCache.removeAssetFromIdCache(contentlet);
		}

		// jira.dotmarketing.net/browse/DOTCMS-1073
		deleteBinaryFiles(contentletsVersion,null);

	}

	public void deleteAllVersionsandBackup(List<Contentlet> contentlets, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		if(contentlets == null || contentlets.size() == 0){
			Logger.info(this, "No contents passed to delete so returning");
			return;
		}
		for (Contentlet con : contentlets)
			if(con.getInode().equals(""))
				throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		List<Contentlet> perCons = perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_PUBLISH, respectFrontendRoles, user);
		List<Contentlet> contentletsVersion = new ArrayList<Contentlet>();
		contentletsVersion.addAll(contentlets);

		if(perCons.size() != contentlets.size()){
			throw new DotSecurityException("User does not have permission to delete some or all of the contentlets");
		}
		for (Contentlet con : contentlets) {
			catAPI.removeChildren(con, APILocator.getUserAPI().getSystemUser(), true);
			catAPI.removeParents(con, APILocator.getUserAPI().getSystemUser(), true);
			List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(con.getStructure());
			for(Relationship relationship :  rels){
				deleteRelatedContent(con,relationship,user,respectFrontendRoles);
			}

			contentletsVersion.addAll(findAllVersions(IdentifierCache.getIdentifierFromIdentifierCache(con.getIdentifier()), user, respectFrontendRoles));
		}

		// jira.dotmarketing.net/browse/DOTCMS-1073
		List<String> contentletInodes = new ArrayList<String>();
		for (Iterator iter = contentletsVersion.iterator(); iter.hasNext();) {
			Contentlet element = (Contentlet) iter.next();
			contentletInodes.add(element.getInode());
		}

		conFac.delete(contentletsVersion);

		for (Contentlet contentlet : perCons) {
			distAPI.addContentIndexEntryToDelete(contentlet.getIdentifier());
			IdentifierCache.removeAssetFromIdCache(contentlet);
		}

		if (contentlets.size() > 0) {
			XStream _xstream = new XStream(new DomDriver());
			Date date = new Date();
			SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss");
			String lastmoddate = sdf.format(date);
			java.io.File _writing = null;

			java.io.File backupFolder = new java.io.File(backupPath);
			if (!backupFolder.exists()) {
				backupFolder.mkdirs();
			}
			_writing = new java.io.File(backupPath + java.io.File.separator + lastmoddate + "_" + "deletedcontentlets" + ".xml");

			BufferedOutputStream _bout = null;
			try {
				_bout = new BufferedOutputStream(new FileOutputStream(_writing));
			} catch (FileNotFoundException e) {

			}
			_xstream.toXML(contentlets, _bout);
		}
		// jira.dotmarketing.net/browse/DOTCMS-1073
		deleteBinaryFiles(contentletsVersion,null);

	}


	public void delete(List<Contentlet> contentlets, User user,	boolean respectFrontendRoles, boolean allVersions) throws DotDataException,DotSecurityException {
		for (Contentlet con : contentlets)
			if(con.getInode().equals(""))
				throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		List<Contentlet> perCons = perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_PUBLISH, respectFrontendRoles, user);
		List<Contentlet> contentletsVersion = new ArrayList<Contentlet>();
		contentletsVersion.addAll(contentlets);

		if(perCons.size() != contentlets.size()){
			throw new DotSecurityException("User does not have permission to delete some or all of the contentlets");
		}
		for (Contentlet con : contentlets) {
			catAPI.removeChildren(con, APILocator.getUserAPI().getSystemUser(), true);
			catAPI.removeParents(con, APILocator.getUserAPI().getSystemUser(), true);
			List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(con.getStructure());
			for(Relationship relationship :  rels){
				deleteRelatedContent(con,relationship,user,respectFrontendRoles);
			}

		}

		// jira.dotmarketing.net/browse/DOTCMS-1073
		List<String> contentletInodes = new ArrayList<String>();
		for (Iterator iter = contentletsVersion.iterator(); iter.hasNext();) {
			Contentlet element = (Contentlet) iter.next();
			contentletInodes.add(element.getInode());
		}

		conFac.delete(contentletsVersion);

		for (Contentlet contentlet : perCons) {
			distAPI.addContentIndexEntryToDelete(contentlet.getIdentifier());
			IdentifierCache.removeAssetFromIdCache(contentlet);
		}

		// jira.dotmarketing.net/browse/DOTCMS-1073
		deleteBinaryFiles(contentletsVersion,null);

	}

	public void deleteVersion(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		if(contentlet == null){
			Logger.info(this, "No contents passed to delete so returning");
			return;
		}
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_PUBLISH, user)){
			throw new DotSecurityException("User does not have permission to delete some or all of the contentlets");
		}

		catAPI.removeChildren(contentlet, APILocator.getUserAPI().getSystemUser(), true);
		catAPI.removeParents(contentlet, APILocator.getUserAPI().getSystemUser(), true);
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(contentlet.getStructure());
		for(Relationship relationship :  rels){
			deleteRelatedContent(contentlet,relationship,user,respectFrontendRoles);
		}

		ArrayList<Contentlet> contentlets = new ArrayList<Contentlet>();
		contentlets.add(contentlet);
		conFac.deleteVersion(contentlet);

		distAPI.addContentIndexEntryToDelete(contentlet.getIdentifier());
		IdentifierCache.removeAssetFromIdCache(contentlet);

		// jira.dotmarketing.net/browse/DOTCMS-1073
		deleteBinaryFiles(contentlets,null);

	}


	public void archive(Contentlet contentlet, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_EDIT, user, respectFrontendRoles)){
			throw new DotSecurityException("User does not have permission to edit the contentlet");
		}
		Contentlet workingContentlet = findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId(), user, respectFrontendRoles);
		Contentlet liveContentlet = null;
		try{
			liveContentlet = findContentletByIdentifier(contentlet.getIdentifier(), true, contentlet.getLanguageId(), user, respectFrontendRoles);
		}catch (DotContentletStateException ce) {
			Logger.debug(this,"No live contentlet found for identifier = " + contentlet.getIdentifier());
		}

		User modUser = null;

		try{
			modUser = APILocator.getUserAPI().loadUserById(workingContentlet.getModUser(),APILocator.getUserAPI().getSystemUser(),false);
		}catch(Exception ex){
			if(ex instanceof NoSuchUserException){
				modUser = APILocator.getUserAPI().getSystemUser();
			}
		}

		if(modUser != null){
			workingContentlet.setModUser(modUser.getUserId());
		}


		if (user == null || !workingContentlet.isLocked() || workingContentlet.getModUser().equals(user.getUserId())) {

			if (liveContentlet != null && InodeUtils.isSet(liveContentlet.getInode())) {
				liveContentlet.setLive(false);
				liveContentlet.setModDate(new Date ());
				conFac.save(liveContentlet);
			}

			//Reset the mod date
			workingContentlet.setModDate(new Date ());
			// sets deleted to true
			workingContentlet.setArchived(true);
			// persists
			conFac.save(workingContentlet);

			// Updating lucene index
			distAPI.addContentIndexEntry(workingContentlet);
		}else{
			throw new DotContentletStateException("Contentlet is locked: Unable to archive");
		}
	}

	public void archive(List<Contentlet> contentlets, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException {
		boolean stateError = false;
		for (Contentlet contentlet : contentlets) {
			try{
				archive(contentlet, user, respectFrontendRoles);
			}catch (DotContentletStateException e) {
				stateError = true;
			}
		}
		if(stateError){
			throw new DotContentletStateException("Unable to archive one or more contentlets because it is locked");
		}

	}

	public void lock(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotContentletStateException, DotDataException,DotSecurityException {
		if(contentlet == null){
			throw new DotContentletStateException("The contentlet cannot Be null");
		}
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_WRITE, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot edit Contentlet");
		}

		if(!UtilMethods.isSet(user))
		{
			user = APILocator.getUserAPI().getSystemUser();
		}

		if(!UtilMethods.isSet(user))
		{
			throw new DotSecurityException("Must specificy user who is locking the contentlet");
		}

		// persists the webasset
		conFac.lock(contentlet.getInode(),user);
		distAPI.addContentIndexEntry(contentlet);
	}

	public void removeContentletFromIndex(String contentletIdentifier) throws DotDataException{
		distAPI.addContentIndexEntryToDelete(contentletIdentifier);
	}

	public void reindex()throws DotReindexStateException {
		try {
			distAPI.addBuildNewIndexEntries();
			ReindexThread.getInstance().startLocalReindex();
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}
	}

	public void reindex(Structure structure)throws DotReindexStateException {
		try {
			distAPI.addStructureReindexEntries(structure.getInode());
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}
	}

	public void reindex(Contentlet contentlet)throws DotReindexStateException, DotDataException{
		distAPI.addContentIndexEntry(contentlet);
	}


	public void refresh(Structure structure) throws DotReindexStateException {
		try {
			distAPI.addStructureReindexEntries(structure.getInode());
			CacheLocator.getContentletCache().clearCache();
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}

	}

	public void refresh(Contentlet contentlet) throws DotReindexStateException,
			DotDataException {
		distAPI.addContentIndexEntry(contentlet);
		CacheLocator.getContentletCache().add(contentlet.getInode(), contentlet);
	}

	public void refreshAllContent() throws DotReindexStateException {
		try {
			distAPI.addBuildNewIndexEntries();
			ReindexThread.getInstance().startLocalReindex();
			CacheLocator.getContentletCache().clearCache();
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}

	}

	public void refreshContentUnderHost(Host host) throws DotReindexStateException {
		try {
			distAPI.refreshContentUnderHost(host);
			CacheLocator.getContentletCache().clearCache();
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}

	}

	public void refreshContentUnderFolder(Folder folder) throws DotReindexStateException {
		try {
			distAPI.refreshContentUnderFolder(folder);
			CacheLocator.getContentletCache().clearCache();
		} catch (DotDataException e) {
			Logger.error(this, e.getMessage(), e);
			throw new DotReindexStateException("Unable to complete reindex",e);
		}

	}

	public void unpublish(Contentlet contentlet, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_PUBLISH, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot unpublish Contentlet");
		}
		Contentlet workingContentlet = findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId(), user, respectFrontendRoles);

		User modUser = null;

		try{
			modUser = APILocator.getUserAPI().loadUserById(workingContentlet.getModUser(),APILocator.getUserAPI().getSystemUser(),false);
		}catch(Exception ex){
			if(ex instanceof NoSuchUserException){
				modUser = APILocator.getUserAPI().getSystemUser();
			}
		}

		if(modUser != null){
			workingContentlet.setModUser(modUser.getUserId());
		}

		if (!(!workingContentlet.isLocked() || workingContentlet.getModUser().equals(user.getUserId()))) {
			throw new DotContentletStateException("Unable to unpublish contentlet because it is locked");
		}
		unpublish(contentlet, user);
	}

	private void unpublish(Contentlet contentlet, User user) throws DotDataException,DotSecurityException, DotContentletStateException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		List<Contentlet> liveCons = new ArrayList<Contentlet>();
		if(InodeUtils.isSet(contentlet.getIdentifier())){
			liveCons = conFac.findContentletsByIdentifier(contentlet.getIdentifier(), true, contentlet.getLanguageId());
		}
		for (Contentlet lcon : liveCons) {
			lcon.setLive(false);
			lcon.setModDate(new java.util.Date());
			lcon.setModUser(user.getUserId());
			conFac.save(lcon);
		}
		//remove contentlet from the live directory
		ContentletServices.unpublishContentletFile(contentlet);
		//writes the contentlet object to a file
		ContentletMapServices.unpublishContentletMapFile(contentlet);
		publishRelatedHtmlPages(contentlet);
		distAPI.addContentIndexEntry(contentlet);

		//Need to refresh the live pages that reference this piece of content
		publishRelatedHtmlPages(contentlet);
	}

	public void unpublish(List<Contentlet> contentlets, User user,boolean respectFrontendRoles) throws DotDataException,	DotSecurityException, DotContentletStateException {
		boolean stateError = false;
		for (Contentlet contentlet : contentlets) {
			try{
				unpublish(contentlet, user, respectFrontendRoles);
			}catch (DotContentletStateException e) {
				stateError = true;
			}
		}
		if(stateError){
			throw new DotContentletStateException("Unable to unpublish one or more contentlets because it is locked");
		}
	}

	public void unarchive(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_PUBLISH, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot unpublish Contentlet");
		}
		Contentlet workingContentlet = findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId(), user, respectFrontendRoles);
		Contentlet liveContentlet = null;

		try{
			liveContentlet = findContentletByIdentifier(contentlet.getIdentifier(), true, contentlet.getLanguageId(), user, respectFrontendRoles);
		}catch (DotContentletStateException ce) {
			Logger.debug(this,"No live contentlet found for identifier = " + contentlet.getIdentifier());
		}
		if(liveContentlet != null && liveContentlet.getInode().equalsIgnoreCase(workingContentlet.getInode()) && !workingContentlet.isArchived())
			throw new DotContentletStateException("Contentlet is unarchivable");
		// sets deleted to true
		workingContentlet.setArchived(false);
		// persists the webasset
		conFac.save(workingContentlet);
		distAPI.addContentIndexEntry(workingContentlet);


		if(liveContentlet != null){
			liveContentlet.setArchived(false);
			conFac.save(liveContentlet);
			distAPI.addContentIndexEntry(liveContentlet);
		}
	}

	public void unarchive(List<Contentlet> contentlets, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		boolean stateError = false;
		for (Contentlet contentlet : contentlets) {
			try{
				unarchive(contentlet, user, respectFrontendRoles);
			}catch (DotContentletStateException e) {
				stateError = true;
			}
		}
		if(stateError){
			throw new DotContentletStateException("Unable to unarchive one or more contentlets because it is locked");
		}
	}

	public void deleteRelatedContent(Contentlet contentlet,Relationship relationship, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException,DotContentletStateException {
		deleteRelatedContent(contentlet, relationship, RelationshipFactory.isParentOfTheRelationship(relationship, contentlet.getStructure()), user, respectFrontendRoles);
	}

	public void deleteRelatedContent(Contentlet contentlet,Relationship relationship, boolean hasParent, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException,DotContentletStateException {
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_EDIT, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot edit Contentlet1");
		}
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(contentlet.getStructure());
		if(!rels.contains(relationship)){
			throw new DotContentletStateException("Contentlet does not have passed in relationship");
		}
		List<Contentlet> cons = getRelatedContent(contentlet, relationship, hasParent, user, respectFrontendRoles);
		cons = perAPI.filterCollection(cons, PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);
		RelationshipFactory.deleteRelationships(contentlet, relationship, cons);
	}

	private void deleteUnrelatedContents(Contentlet contentlet, ContentletRelationshipRecords related, boolean hasParent, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException,DotContentletStateException {
		if (!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_EDIT, user, respectFrontendRoles)) {
			throw new DotSecurityException("User cannot edit Contentlet1");
		}
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(contentlet.getStructure());
		if (!rels.contains(related.getRelationship())) {
			throw new DotContentletStateException("Contentlet does not have passed in relationship");
		}
		List<Contentlet> cons = getRelatedContent(contentlet, related.getRelationship(), hasParent, user, respectFrontendRoles);
		cons = perAPI.filterCollection(cons, PermissionAPI.PERMISSION_READ, respectFrontendRoles, user);

		boolean contentSelected;
		Tree tree;
		for (Contentlet relatedContent: cons) {
			contentSelected = false;

			for (Contentlet selectedRelatedContent: related.getRecords()) {
				if (selectedRelatedContent.getIdentifier().equals(relatedContent.getIdentifier())) {
					contentSelected = true;
					break;
				}
			}

			if (!contentSelected) {
				if (related.isHasParent()) {
					tree = TreeFactory.getTree(contentlet.getIdentifier(), relatedContent.getIdentifier(), related.getRelationship().getRelationTypeValue());
				} else {
					tree = TreeFactory.getTree(relatedContent.getIdentifier(), contentlet.getIdentifier(), related.getRelationship().getRelationTypeValue());
				}
				TreeFactory.deleteTree(tree);
			}
		}
	}

	public void relateContent(Contentlet contentlet, Relationship rel, List<Contentlet> records, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException, DotContentletStateException {
		Structure st = StructureCache.getStructureByInode(contentlet.getStructureInode());
		boolean hasParent = RelationshipFactory.isParentOfTheRelationship(rel, st);
		ContentletRelationshipRecords related = new ContentletRelationships(contentlet).new ContentletRelationshipRecords(rel, hasParent);
		related.setRecords(records);
		relateContent(contentlet, related, user, respectFrontendRoles);
	}

	private Tree getTree(String parent, String child, String relationType, List<Tree> trees) {
		Tree result = new Tree();

		for (Tree tree: trees) {
			if ((tree.getParent().equals(parent)) &&
				(tree.getChild().equals(child)) &&
				(tree.getRelationType().equals(relationType))) {
				//try {
				//	BeanUtils.copyProperties(result, tree);
				//} catch (Exception e) {
				//}
				//return result;
				return tree;
			}
		}

		return result;
	}

	public void relateContent(Contentlet contentlet, ContentletRelationshipRecords related, User user, boolean respectFrontendRoles)throws DotDataException, DotSecurityException, DotContentletStateException {
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_EDIT, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot edit Contentlet1");
		}
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(contentlet.getStructure());
		if(!rels.contains(related.getRelationship())){
			throw new DotContentletStateException("Contentlet does not have passed in relationship");
		}

		boolean child = !related.isHasParent();

		List<Tree> contentParents = null;
		if (child)
			contentParents = TreeFactory.getTreesByChild(contentlet.getIdentifier());

		deleteUnrelatedContents(contentlet, related, related.isHasParent(), user, respectFrontendRoles);
		Tree newTree = null;
		Set<Tree> uniqueRelationshipSet = new HashSet<Tree>();

		Relationship rel = related.getRelationship();
		List<Contentlet> conRels = RelationshipFactory.getAllRelationshipRecords(related.getRelationship(), contentlet, related.isHasParent());

		int treePosition = (conRels != null && conRels.size() != 0) ? conRels.size() : 1 ;
		int positionInParent;
		List<Tree> trees;
		for (Contentlet c : related.getRecords()) {
			if (child) {
				//newTree = TreeFactory.getTree(c.getIdentifier(), contentlet.getIdentifier(), rel.getRelationTypeValue());
				newTree = getTree(c.getIdentifier(), contentlet.getIdentifier(), rel.getRelationTypeValue(), contentParents);
				if(!InodeUtils.isSet(newTree.getParent())) {
					try {
						positionInParent = 0;
						trees = TreeFactory.getTreesByParent(c.getIdentifier());
						for (Tree tree: trees) {
							if ((tree.getRelationType().equals(rel.getRelationTypeValue())) && (positionInParent <= tree.getTreeOrder())) {
								positionInParent = tree.getTreeOrder() + 1;
							}
						}
					} catch (Exception e) {
						positionInParent = 0;
					}
					if(positionInParent == 0)//DOTCMS-6878
						positionInParent = treePosition;
					newTree = new Tree(c.getIdentifier(), contentlet.getIdentifier(), rel.getRelationTypeValue(), positionInParent);
				}else{
					if(newTree.getTreeOrder() == 0)//DOTCMS-6855
						newTree.setTreeOrder(treePosition);
					else
						treePosition = newTree.getTreeOrder();//DOTCMS-6878
				}
			} else {
				newTree = TreeFactory.getTree(contentlet.getIdentifier(), c.getIdentifier(), rel.getRelationTypeValue());
				if(!InodeUtils.isSet(newTree.getParent()))
					newTree = new Tree(contentlet.getIdentifier(), c.getIdentifier(), rel.getRelationTypeValue(), treePosition);
				else
					newTree.setTreeOrder(treePosition);
			}
			//newTree.setTreeOrder(treePosition);
			if( uniqueRelationshipSet.add(newTree) ) {
				TreeFactory.saveTree(newTree);
				treePosition++;
			}
		}
	}

	public void publish(List<Contentlet> contentlets, User user,	boolean respectFrontendRoles) throws DotSecurityException,DotDataException, DotContentletStateException {
		boolean stateError = false;
		for (Contentlet contentlet : contentlets) {
			try{
				publish(contentlet, user, respectFrontendRoles);
			}catch (DotContentletStateException e) {
				stateError = true;
			}
		}
		if(stateError){
			throw new DotContentletStateException("Unable to publish one or more contentlets because it is locked");
		}
	}


	public boolean isContentEqual(Contentlet contentlet1,Contentlet contentlet2, User user, boolean respectFrontendRoles)throws DotSecurityException, DotDataException {
		if(!perAPI.doesUserHavePermission(contentlet1, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet1");
		}
		if(!perAPI.doesUserHavePermission(contentlet2, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet2");
		}
		if(contentlet1.getInode().equalsIgnoreCase(contentlet2.getInode())){
			return true;
		}
		return false;
	}

	public List<Contentlet> getSiblings(String identifier)throws DotDataException {
		List<Contentlet> contentletList = conFac.getContentletsByIdentifier(identifier );

		return contentletList;
	}

	public Contentlet checkin(Contentlet contentlet, List<Category> cats, List<Permission> permissions, User user, boolean respectFrontendRoles) throws IllegalArgumentException,DotDataException, DotSecurityException,DotContentletStateException, DotContentletValidationException {

		Map<Relationship, List<Contentlet>> contentRelationships = null;
		Contentlet workingCon = null;

		//If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			contentRelationships = findContentRelationships(workingCon);
		}
		else
		{
			contentRelationships = findContentRelationships(contentlet);
		}

		if(permissions == null)
			permissions = new ArrayList<Permission>();
		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;

		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);

	}

	public Contentlet checkin(Contentlet contentlet, List<Permission> permissions, User user, boolean respectFrontendRoles) throws IllegalArgumentException,DotDataException, DotSecurityException,DotContentletStateException, DotContentletValidationException {

		List<Category> cats = null;
		Map<Relationship, List<Contentlet>> contentRelationships = null;
		Contentlet workingCon = null;

        //If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			cats = catAPI.getParents(contentlet, APILocator.getUserAPI().getSystemUser(), true);
			contentRelationships = findContentRelationships(workingCon);
		}
		else
		{
			contentRelationships = findContentRelationships(contentlet);
		}

		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet,Map<Relationship, List<Contentlet>> contentRelationships,List<Category> cats, User user, boolean respectFrontendRoles)throws IllegalArgumentException, DotDataException,DotSecurityException, DotContentletStateException,DotContentletValidationException {

		List<Permission> permissions = null;
		Contentlet workingCon = null;

		//If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			permissions = perAPI.getPermissions(workingCon);
		}

		if(permissions == null)
			permissions = new ArrayList<Permission>();

		if(permissions == null)
			permissions = new ArrayList<Permission>();
		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet,Map<Relationship, List<Contentlet>> contentRelationships,User user, boolean respectFrontendRoles)throws IllegalArgumentException, DotDataException,	DotSecurityException, DotContentletStateException,DotContentletValidationException {

		List<Permission> permissions = null;
		List<Category> cats = null;
		Contentlet workingCon = null;

		//If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			permissions = perAPI.getPermissions(workingCon);
			cats = catAPI.getParents(contentlet, APILocator.getUserAPI().getSystemUser(), true);
		}

		if(permissions == null)
			permissions = new ArrayList<Permission>();
		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet, User user,boolean respectFrontendRoles) throws IllegalArgumentException,DotDataException, DotSecurityException,DotContentletStateException, DotContentletValidationException {

		List<Permission> permissions = null;
		List<Category> cats = null;
		Map<Relationship, List<Contentlet>> contentRelationships = null;
		Contentlet workingCon = null;

		//If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			permissions = perAPI.getPermissions(workingCon);
			cats = catAPI.getParents(workingCon, APILocator.getUserAPI().getSystemUser(), true);
			contentRelationships = findContentRelationships(workingCon);
		}
		else
		{
			contentRelationships = findContentRelationships(contentlet);
		}

		if(permissions == null)
			permissions = new ArrayList<Permission>();
		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet, User user,boolean respectFrontendRoles, List<Category> cats)throws IllegalArgumentException, DotDataException,DotSecurityException, DotContentletStateException,DotContentletValidationException {

		List<Permission> permissions = null;
		Map<Relationship, List<Contentlet>> contentRelationships = null;
		Contentlet workingCon = null;

		//If contentlet is not new
		if(InodeUtils.isSet(contentlet.getIdentifier())) {
			workingCon = findWorkingContentlet(contentlet);
			permissions = perAPI.getPermissions(workingCon, false, true);
			contentRelationships = findContentRelationships(workingCon);
		}
		else
		{
			contentRelationships = findContentRelationships(contentlet);
		}

		if(permissions == null)
			permissions = new ArrayList<Permission>();
		if(cats == null)
			cats = new ArrayList<Category>();
		if(contentRelationships == null)
			contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(workingCon == null)
			workingCon = contentlet;
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet, Map<Relationship, List<Contentlet>> contentRelationships, List<Category> cats ,List<Permission> permissions, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException, DotContentletValidationException {
		Structure st = StructureCache.getStructureByInode(contentlet.getStructureInode());
		ContentletRelationships relationshipsData = new ContentletRelationships(contentlet);
		List<ContentletRelationshipRecords> relationshipsRecords = new ArrayList<ContentletRelationshipRecords> ();
		relationshipsData.setRelationshipsRecords(relationshipsRecords);
		for(Entry<Relationship, List<Contentlet>> relEntry : contentRelationships.entrySet()) {
			Relationship relationship = (Relationship) relEntry.getKey();
			boolean hasParent = RelationshipFactory.isParentOfTheRelationship(relationship, st);
			ContentletRelationshipRecords records = relationshipsData.new ContentletRelationshipRecords(relationship, hasParent);
			records.setRecords(relEntry.getValue());
			relationshipsRecords.add(records);
		}
		return checkin(contentlet, relationshipsData, cats, permissions, user, respectFrontendRoles);
	}

	public Contentlet checkin(Contentlet contentlet, ContentletRelationships contentRelationships, List<Category> cats ,List<Permission> permissions, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException, DotContentletValidationException {
		return checkin(contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles, true);
	}

	public Contentlet checkinWithoutVersioning(Contentlet contentlet, Map<Relationship, List<Contentlet>> contentRelationships, List<Category> cats ,List<Permission> permissions, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException, DotContentletValidationException {
		Structure st = StructureCache.getStructureByInode(contentlet.getStructureInode());
		ContentletRelationships relationshipsData = new ContentletRelationships(contentlet);
		List<ContentletRelationshipRecords> relationshipsRecords = new ArrayList<ContentletRelationshipRecords> ();
		relationshipsData.setRelationshipsRecords(relationshipsRecords);
		for(Entry<Relationship, List<Contentlet>> relEntry : contentRelationships.entrySet()) {
			Relationship relationship = (Relationship) relEntry.getKey();
			boolean hasParent = RelationshipFactory.isParentOfTheRelationship(relationship, st);
			ContentletRelationshipRecords records = relationshipsData.new ContentletRelationshipRecords(relationship, hasParent);
			records.setRecords(relEntry.getValue());
			relationshipsRecords.add(records);
		}
		return checkin(contentlet, relationshipsData, cats , permissions, user, respectFrontendRoles, false);
	}


	private Contentlet checkin(Contentlet contentlet, ContentletRelationships contentRelationships, List<Category> cats, List<Permission> permissions,
			User user, boolean respectFrontendRoles, boolean createNewVersion) throws DotDataException, DotSecurityException, DotContentletStateException,
			DotContentletValidationException {

		String syncMe = (UtilMethods.isSet(contentlet.getIdentifier())) ? contentlet.getIdentifier() : UUIDGenerator.generateUuid();

		synchronized (syncMe) {

			if (createNewVersion && contentlet != null && InodeUtils.isSet(contentlet.getInode()))
				throw new DotContentletStateException("Contentlet must not exist already");
			if (!createNewVersion && contentlet != null && !InodeUtils.isSet(contentlet.getInode()))
				throw new DotContentletStateException("Contentlet must exist already");
			if (contentlet != null && contentlet.isArchived())
				throw new DotContentletStateException("Unable to checkin an archived piece of content, please un-archive first");
			if (!perAPI.doesUserHavePermission(InodeUtils.isSet(contentlet.getIdentifier()) ? contentlet : contentlet.getStructure(),
					PermissionAPI.PERMISSION_WRITE, user, respectFrontendRoles)) {
				List<Role> rolesPublish = perAPI.getRoles(contentlet.getStructure().getPermissionId(), PermissionAPI.PERMISSION_PUBLISH, "CMS Owner", 0, -1);
				List<Role> rolesWrite = perAPI.getRoles(contentlet.getStructure().getPermissionId(), PermissionAPI.PERMISSION_WRITE, "CMS Owner", 0, -1);
				Role cmsOwner = APILocator.getRoleAPI().loadCMSOwnerRole();
				boolean isCMSOwner = false;
				if (rolesPublish.size() > 0 || rolesWrite.size() > 0) {
					for (Role role : rolesPublish) {
						if (role.getId().equals(cmsOwner.getId())) {
							isCMSOwner = true;
							break;
						}
					}
					if (!isCMSOwner) {
						for (Role role : rolesWrite) {
							if (role.getId().equals(cmsOwner.getId())) {
								isCMSOwner = true;
								break;
							}
						}
					}
					if (!isCMSOwner) {
						throw new DotSecurityException("User doesn't have write permissions to Contentlet");
					}
				} else {
					throw new DotSecurityException("User doesn't have write permissions to Contentlet");
				}
			}
			if (createNewVersion && (contentRelationships == null || cats == null || permissions == null))
				throw new IllegalArgumentException(
						"The categories, permissions and content relationships cannot be null when trying to checkin. The method was called improperly");
			try {
				validateContentlet(contentlet, contentRelationships, cats);
			} catch (DotContentletValidationException ve) {
				throw ve;
			}

			boolean priority = contentlet.isLowIndexPriority();
			boolean isNewContent = true;

			//Setting all current working copies of content off for the given language
			String workingContentletInode = "";

			contentlet.setWorking(true);
			if (contentlet.getLanguageId() == 0) {
				Language defaultLanguage = lanAPI.getDefaultLanguage();
				contentlet.setLanguageId(defaultLanguage.getId());
			}
			List<Contentlet> workingCons = new ArrayList<Contentlet>();
			if (InodeUtils.isSet(contentlet.getIdentifier())) {
				workingCons = conFac.findContentletsByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId());
			}

			Contentlet workingContentlet = null;
			for (Contentlet workingCon : workingCons) {
				isNewContent = false;
				if (createNewVersion || (!createNewVersion && workingCon.getInode().equalsIgnoreCase(contentlet.getInode()))) {
					workingContentlet = workingCon;
					workingCon.setWorking(false);
					workingContentletInode = workingContentlet.getInode(); // http://jira.dotmarketing.net/browse/DOTCMS-1073
					conFac.save(workingCon);
				}
			}
			if(workingContentlet != null){
				if(!contentlet.getStructureInode().equals(workingContentlet.getStructureInode())){
					throw new DotContentletStateException("Contentlet Structure Inodes Must be the Same.");
				}
			}

			//Setting all current live content of the same language off if needs to publish this new content
			if(!isNewContent && contentlet.isLive())
				setLiveContentOff(contentlet);

			// save temporally live state
			final boolean currContentletLiveState = contentlet.isLive();
			contentlet.setLocked(false);
			contentlet.setModDate(new Date());
			contentlet.setModUser(user != null ? user.getUserId() : "");

			if (contentlet.getOwner() == null || contentlet.getOwner().length() < 1) {
				contentlet.setOwner(user.getUserId());
			}

			// check contentlet Host
			if (!UtilMethods.isSet(contentlet.getHost())) {
				contentlet.setHost(APILocator.getHostAPI().findSystemHost(APILocator.getUserAPI().getSystemUser(), true).getIdentifier());
			}
			if (!UtilMethods.isSet(contentlet.getFolder())) {
				contentlet.setFolder(FolderFactory.getSystemFolder().getInode());
			}
			Contentlet contentletRaw=contentlet;

			contentlet = conFac.save(contentlet);

			if (!InodeUtils.isSet(contentlet.getIdentifier())) {
				Host host = APILocator.getHostAPI().find(contentlet.getHost(), APILocator.getUserAPI().getSystemUser(), false);
				Identifier ident = IdentifierFactory.createNewIdentifier(contentlet, host);
				contentlet.setIdentifier(ident.getInode());
				contentlet = conFac.save(contentlet);
			} else {
				Identifier ident = IdentifierFactory.getIdentifierByInode(contentlet);
				ident.setHostId(contentlet.getHost());
				InodeFactory.saveInode(ident);
				IdentifierCache.removeFromIdCacheByInode(ident.getInode());
				IdentifierCache.addIdentifierToIdentifierCache(ident);

				try{
					APILocator.getRelationshipAPI().addRelationship(contentlet.getIdentifier(), contentlet.getInode(), "child");
				} catch (Exception e) {
					Logger.error(IdentifierFactory.class, "Unable to add relationship to identifier", e);
				}
			}

			boolean structureHasAHostField = hasAHostField(contentlet.getStructureInode());

			List<Field> fields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());
			Field hostField = new Field();

			for (Field field : fields) {
				if (field.getFieldType().equals(Field.FieldType.HOST_OR_FOLDER.toString()))
					hostField = field;
			}

			for (Field field : fields) {
				if (isFieldTypeString(field)) {
					String value = contentlet.getStringProperty(field.getVelocityVarName());
					if (field.getFieldType().equals(Field.FieldType.TAG.toString()) && UtilMethods.isSet(value)) {
						String[] tagNames = ((String) value).split(",");
						if(structureHasAHostField){
							Host host = null;
							String hostId = "";
							try{
								host = APILocator.getHostAPI().find(contentlet.getHost(), user, true);
							}catch(Exception e){
								Logger.error(this, "Unable to get default host");
							}
							if(host.getIdentifier().equals(Host.SYSTEM_HOST))
								hostId = Host.SYSTEM_HOST;
							else
								hostId = host.getIdentifier();
							for (String tagName : tagNames)
								try {
									tagAPI.addTagInode(tagName.trim(), contentlet.getInode(), hostId);
								} catch (Exception e) {
									e.printStackTrace();
								}
						}
						else {
							for (String tagName : tagNames)
								try {
									tagAPI.addTagInode(tagName.trim(), contentlet.getInode(), Host.SYSTEM_HOST);
								} catch (Exception e) {
									e.printStackTrace();
								}
						}
					}
				}
			}


			// update owner
			// APILocator.getPermissionAPI().updateOwner(contentlet,
			// contentlet.getOwner());

			contentlet.setLive(currContentletLiveState);

			if (workingContentlet == null) {
				workingContentlet = contentlet;
			}

			// DOTCMS-4732
//			if(isNewContent && !hasAHostFieldSet(contentlet.getStructureInode(),contentlet)){
//				List<Permission> stPers = perAPI.getPermissions(contentlet.getStructure());
//				if(stPers != null && stPers.size()>0){
//					if(stPers.get(0).isIndividualPermission()){
//						perAPI.copyPermissions(contentlet.getStructure(), contentlet);
//					}
//				}
//			}else{
//				perAPI.resetPermissionReferences(contentlet);
//			}

			if (createNewVersion || (!createNewVersion && (contentRelationships != null || cats != null))) {
				moveContentDependencies(workingContentlet, contentlet, contentRelationships, cats, permissions, user, respectFrontendRoles);
			}

			// Refreshing permissions
			if (hasAHostField(contentlet.getStructureInode()) && !isNewContent) {
				perAPI.resetPermissionReferences(contentlet);
			}

			// Publish once if needed and reindex once if needed. The publish
			// method reindexes.
			contentlet.setLowIndexPriority(priority);

			if (contentlet.isLive()) {
				finishPublish(contentlet, isNewContent, createNewVersion);
			} else {
				if (!isNewContent) {
					ContentletServices.invalidate(contentlet, true);
					// writes the contentlet object to a file
					ContentletMapServices.invalidate(contentlet, true);
				}

				APILocator.getDistributedJournalAPI().addContentIndexEntry(contentlet);
			}

			// http://jira.dotmarketing.net/browse/DOTCMS-1073
			// storing binary files in file system.
			Logger.debug(this, "ContentletAPIImpl : storing binary files in file system.");

			if(createNewVersion){
				List<Field> structFields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());
				for (Field field : structFields) {
					if (field.getFieldContentlet().startsWith("binary")) {
						try {
							Object binaryFileName = null;
							String velocityVarNm = field.getVelocityVarName();
							java.io.File tempFile = contentletRaw.getBinary(velocityVarNm);
							contentlet.setBinary(velocityVarNm, tempFile);

							if (tempFile != null) {
								binaryFileName = tempFile.getName();
							}
							if (!UtilMethods.isSet(binaryFileName) || binaryFileName.toString().contains("-removed-")){
								continue;
							}
							String newContentletInode = contentlet.getInode();
							java.io.File srcFile;

							java.io.File workingInodeFile = null;

							if (InodeUtils.isSet(workingContentletInode)) {
								workingInodeFile = new java.io.File(FileFactory.getRealAssetPath() + java.io.File.separator + workingContentletInode.charAt(0)
										+ java.io.File.separator + workingContentletInode.charAt(1) + java.io.File.separator + workingContentletInode
										+ java.io.File.separator + velocityVarNm + java.io.File.separator + binaryFileName);
							}

							if (tempFile.exists()) {
								srcFile = tempFile;
							} else {
								if ((workingInodeFile == null) || !workingInodeFile.exists()){
									Logger.debug(this,"The file must be set");
									continue;
								}
								srcFile = workingInodeFile;
							}
							String newInodeAssetFolderPath = FileFactory.getRealAssetPath() + java.io.File.separator + newContentletInode.charAt(0)
							+ java.io.File.separator + newContentletInode.charAt(1) + java.io.File.separator + newContentletInode + java.io.File.separator
							+ velocityVarNm;

							java.io.File newInodeAssetFolder = new java.io.File(newInodeAssetFolderPath);

							if (!newInodeAssetFolder.exists())
								newInodeAssetFolder.mkdirs();

							java.io.File destFile = new java.io.File(newInodeAssetFolder.getAbsolutePath() + java.io.File.separator + srcFile.getName());

							if (srcFile.equals(workingInodeFile)) {
								if(!srcFile.equals(destFile)){//DOTCMS-5063
									FileChannel ic = new FileInputStream(srcFile).getChannel();
									FileChannel oc = new FileOutputStream(destFile).getChannel();
									ic.transferTo(0, ic.size(), oc);
									ic.close();
									oc.close();
								}
							} else {
								if(!srcFile.renameTo(destFile)){
									FileUtils.moveFile(srcFile, destFile);
								}
							}
						} catch (FileNotFoundException e) {
							e.printStackTrace();
							throw new DotContentletValidationException("Error occurred while processing the file. ");
						} catch (IOException e) {
							e.printStackTrace();
							throw new DotContentletValidationException("Error occurred while processing the file.");
						}
					}
				}
			}


			Structure hostStructure = StructureCache.getStructureByVelocityVarName("Host");
			if ((contentlet != null) && InodeUtils.isSet(contentlet.getIdentifier()) && contentlet.getStructureInode().equals(hostStructure.getInode())) {
				HostAPI hostAPI = APILocator.getHostAPI();
				hostAPI.updateCache(new Host(contentlet));
				hostAPI.updateVirtualLinks(new Host(workingContentlet), new Host(contentlet));//DOTCMS-5025
				hostAPI.updateMenuLinks(new Host(workingContentlet), new Host(contentlet));

				//update tag references
				String oldTagStorageId = workingContentlet.getMap().get("tagStorage").toString();
				String newTagStorageId = contentlet.getMap().get("tagStorage").toString();

				tagAPI.updateTagReferences(contentlet.getIdentifier(), oldTagStorageId, newTagStorageId);
			}

		} // end syncronized block
		return contentlet;
	}

	public List<Contentlet> checkout(List<Contentlet> contentlets, User user,	boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		List<Contentlet> result = new ArrayList<Contentlet>();
		for (Contentlet contentlet : contentlets) {
			result.add(checkout(contentlet.getInode(), user, respectFrontendRoles));
		}
		return result;
	}

	public List<Contentlet> checkoutWithQuery(String luceneQuery, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException, ParseException {
		List<Contentlet> result = new ArrayList<Contentlet>();
		List<Contentlet> cons = search(luceneQuery, 0, -1, "", user, respectFrontendRoles);
		for (Contentlet contentlet : cons) {
			result.add(checkout(contentlet.getInode(), user, respectFrontendRoles));
		}
		return result;
	}

	public List<Contentlet> checkout(String luceneQuery, User user,boolean respectFrontendRoles, int offset, int limit) throws DotDataException,DotSecurityException, DotContentletStateException, ParseException {
		List<Contentlet> result = new ArrayList<Contentlet>();
		List<Contentlet> cons = search(luceneQuery, limit, offset, "", user, respectFrontendRoles);
		for (Contentlet contentlet : cons) {
			result.add(checkout(contentlet.getInode(), user, respectFrontendRoles));
		}
		return result;
	}

	public Contentlet checkout(String contentletInode, User user,boolean respectFrontendRoles) throws DotDataException,DotSecurityException, DotContentletStateException {
		//return new version
		Contentlet contentlet = find(contentletInode, user, respectFrontendRoles);

		if(contentlet == null){
			throw new DotContentletStateException("");
		}
		Contentlet workingContentlet = new Contentlet();
		Map<String, Object> cmap = contentlet.getMap();
		workingContentlet.setStructureInode(contentlet.getStructureInode());
		workingContentlet.setInode(contentletInode);
		copyProperties(workingContentlet, cmap);
		workingContentlet.setLive(false);
		workingContentlet.setWorking(true);
		workingContentlet.setInode("");
		return workingContentlet;
	}

	private void moveContentDependencies(Contentlet fromContentlet, Contentlet toContentlet, ContentletRelationships contentRelationships, List<Category> categories ,List<Permission> permissions, User user,boolean respect) throws DotDataException, DotSecurityException{

		//Handles Categories
		List<Category> categoriesUserCannotRemove = new ArrayList<Category>();
		if(categories == null){
			categories = new ArrayList<Category>();
		}
		//Find categories which the user can't use.  A user cannot remove a category they cannot use
		List<Category> cats = catAPI.getParents(fromContentlet, APILocator.getUserAPI().getSystemUser(), true);
		for (Category category : cats) {
			if(!catAPI.canUseCategory(category, user, false)){
				if(!categories.contains(category)){
					categoriesUserCannotRemove.add(category);
				}
			}
		}
		categories = perAPI.filterCollection(categories, PermissionAPI.PERMISSION_USE, respect, user);
		categories.addAll(categoriesUserCannotRemove);
		if(!categories.isEmpty())
		   catAPI.setParents(toContentlet, categories, user, respect);


		//Handle Relationships

		if(contentRelationships == null){
			contentRelationships = new ContentletRelationships(toContentlet);
		}
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(fromContentlet.getStructure());
		for (Relationship r : rels) {
			if(RelationshipFactory.isSameStructureRelationship(r, fromContentlet.getStructure())) {
				ContentletRelationshipRecords selectedRecords = null;

				//First all relationships as parent
				for(ContentletRelationshipRecords records : contentRelationships.getRelationshipsRecords()) {
					if(records.getRelationship().getInode().equalsIgnoreCase(r.getInode()) && records.isHasParent()) {
						selectedRecords = records;
						break;
					}
				}
				if (selectedRecords == null) {
					selectedRecords = contentRelationships.new ContentletRelationshipRecords(r, true);
					contentRelationships.getRelationshipsRecords().add(contentRelationships.new ContentletRelationshipRecords(r, true));
				}

				//Adding to the list all the records the user was not able to see becuase permissions forcing them into the relationship
				List<Contentlet> cons = getRelatedContent(fromContentlet, r, true, APILocator.getUserAPI().getSystemUser(), true);
				for (Contentlet contentlet : cons) {
					if (!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, false)) {
						selectedRecords.getRecords().add(0, contentlet);
					}
				}

				//Then all relationships as child
				for(ContentletRelationshipRecords records : contentRelationships.getRelationshipsRecords()) {
					if(records.getRelationship().getInode().equalsIgnoreCase(r.getInode()) && !records.isHasParent()) {
						selectedRecords = records;
						break;
					}
				}
				if (selectedRecords == null) {
					selectedRecords = contentRelationships.new ContentletRelationshipRecords(r, false);
					contentRelationships.getRelationshipsRecords().add(contentRelationships.new ContentletRelationshipRecords(r, false));
				}

				//Adding to the list all the records the user was not able to see becuase permissions forcing them into the relationship
				cons = getRelatedContent(fromContentlet, r, false, APILocator.getUserAPI().getSystemUser(), true);
				for (Contentlet contentlet : cons) {
					if (!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, false)) {
						selectedRecords.getRecords().add(0, contentlet);
					}
				}

			} else {
				ContentletRelationshipRecords selectedRecords = null;

				//First all relationships as parent
				for(ContentletRelationshipRecords records : contentRelationships.getRelationshipsRecords()) {
					if(records.getRelationship().getInode().equalsIgnoreCase(r.getInode())) {
						selectedRecords = records;
						break;
					}
				}
				boolean hasParent = RelationshipFactory.isParentOfTheRelationship(r, fromContentlet.getStructure());
				if (selectedRecords == null) {
					selectedRecords = contentRelationships.new ContentletRelationshipRecords(r, hasParent);
					contentRelationships.getRelationshipsRecords().add(contentRelationships.new ContentletRelationshipRecords(r, hasParent));
				}

				//Adding to the list all the records the user was not able to see because permissions forcing them into the relationship
				List<Contentlet> cons = getRelatedContent(fromContentlet, r, APILocator.getUserAPI().getSystemUser(), true);
				for (Contentlet contentlet : cons) {
					if (!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, false)) {
						selectedRecords.getRecords().add(0, contentlet);
					}
				}
			}
		}
		for (ContentletRelationshipRecords cr : contentRelationships.getRelationshipsRecords()) {
			relateContent(toContentlet, cr, APILocator.getUserAPI().getSystemUser(), true);
		}
	}

	public void restoreVersion(Contentlet contentlet, User user,boolean respectFrontendRoles) throws DotSecurityException, DotContentletStateException, DotDataException {
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_EDIT, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot edit Contentlet");
		}
		if(contentlet == null){
			throw new DotContentletStateException("The contentlet was null");
		}
		Contentlet currentWorkingCon = findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId(), user, respectFrontendRoles);
		currentWorkingCon.setWorking(false);
		contentlet.setWorking(true);
		conFac.save(currentWorkingCon);
		conFac.save(contentlet);
		// Upodating lucene index
		ContentletServices.invalidate(contentlet, true);
		//writes the contentlet object to a file
		ContentletMapServices.invalidate(contentlet, true);
		// Updating lucene index
		distAPI.addContentIndexEntry(currentWorkingCon);
		distAPI.addContentIndexEntry(contentlet);
	}

	public List<Contentlet> findAllUserVersions(Identifier identifier,User user, boolean respectFrontendRoles) throws DotSecurityException, DotDataException, DotStateException {
		List<Contentlet> contentlets = conFac.findAllUserVersions(identifier);
		if(contentlets.isEmpty())
			return new ArrayList<Contentlet>();
		if(!perAPI.doesUserHavePermission(contentlets.get(0), PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet So Unable to View Versions");
		}
		return contentlets;
	}

	public List<Contentlet> findAllVersions(Identifier identifier, User user,boolean respectFrontendRoles) throws DotSecurityException,DotDataException, DotStateException {
		List<Contentlet> contentlets = conFac.findAllVersions(identifier);
		if(contentlets.isEmpty())
			return new ArrayList<Contentlet>();
		if(!perAPI.doesUserHavePermission(contentlets.get(0), PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet So Unable to View Versions");
		}
		return contentlets;
	}

	public String getName(Contentlet contentlet, User user,	boolean respectFrontendRoles) throws DotSecurityException,DotContentletStateException, DotDataException {
		if(!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)){
			throw new DotSecurityException("User cannot read Contentlet1");
		}
		if(contentlet == null){
			throw new DotContentletStateException("The contentlet was null");
		}

		List<Field> fields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());

		for (Field fld : fields) {

			try{

				if(fld.isListed()){
					String returnValue = contentlet.getMap().get(fld.getVelocityVarName()).toString();
					returnValue = returnValue.length() > 250 ? returnValue.substring(0,250) : returnValue;
					return returnValue;
				}
			}
			catch(Exception e){
				Logger.warn(this.getClass(), "unable to get field value " + fld.getVelocityVarName() + " " + e);
			}
		}
		return "";
	}

	/**
	 * This is the original method that copy the properties of one contentlet to another, this is tge original firm and call the overloaded firm with checkIsUnique false
	 */

	public void copyProperties(Contentlet contentlet,Map<String, Object> properties) throws DotContentletStateException,DotSecurityException {
		boolean checkIsUnique = false;
		copyProperties(contentlet,properties, checkIsUnique);
	}

	/**
	 * This is the new method of the copyProperties that copy one contentlet to another, the checkIsUnique should be by default false, it check if a String value is
	 * unique and add a (Copy) string to the end of the field value, this method is called several times, so is important to call it with checkIsUnique false all the times
	 * @param contentlet the new contentlet to the filled
	 * @param properties the map with the fields and values of the old contentlet
	 * @param checkIsUnique the variable that establish if the unique string values should be modified or not
	 * @throws DotContentletStateException
	 * @throws DotSecurityException
	 */

	public void copyProperties(Contentlet contentlet,Map<String, Object> properties,boolean checkIsUnique) throws DotContentletStateException,DotSecurityException {
		if(!InodeUtils.isSet(contentlet.getStructureInode())){
			Logger.warn(this,"Cannot copy properties to contentlet where structure inode < 1 : You must set the structure's inode");
			return;
		}
		List<Field> fields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());
		List<String> fieldNames = new ArrayList<String>();
		Map<String, Field> velFieldmap = new HashMap<String, Field>();

		for (Field field : fields) {
			if(!field.getFieldType().equals(Field.FieldType.LINE_DIVIDER.toString()) && !field.getFieldType().equals(Field.FieldType.TAB_DIVIDER.toString())){
			fieldNames.add(field.getFieldName());
			velFieldmap.put(field.getVelocityVarName(),field);
			}
		}
		for (Map.Entry<String, Object> property : properties.entrySet()) {
			if(fieldNames.contains(property.getKey())){
				Logger.debug(this, "The map found a field not within the contentlet's structure");
			}
			if(property.getValue() == null)
				continue;
			if((!property.getKey().equals("recurrence"))&&!(property.getValue() instanceof String || property.getValue() instanceof Boolean ||property.getValue() instanceof java.io.File || property.getValue() instanceof Float || property.getValue() instanceof Integer || property.getValue() instanceof Date || property.getValue() instanceof Long || property.getValue() instanceof List)){
				throw new DotContentletStateException("The map contains an invalid value");
			}
		}

		for (Map.Entry<String, Object> property : properties.entrySet()) {
			String conVariable = property.getKey();
			Object value = property.getValue();
			if(value!=null && value instanceof String && ((String)value).indexOf("\\u")>-1) {
				value = ((String)value).replace("\\u", "${esc.b}u");
			}
			try{
				if(conVariable.equals(Contentlet.INODE_KEY)){
					contentlet.setInode((String)value);
				}else if(conVariable.equals(Contentlet.LANGUAGEID_KEY)){
					contentlet.setLanguageId((Long)value);
				}else if(conVariable.equals(Contentlet.STRUCTURE_INODE_KEY)){
					contentlet.setStructureInode((String)value);
				}else if(conVariable.equals(Contentlet.LAST_REVIEW_KEY)){
					contentlet.setLastReview((Date)value);
				}else if(conVariable.equals(Contentlet.NEXT_REVIEW_KEY)){
					contentlet.setNextReview((Date)value);
				}else if(conVariable.equals(Contentlet.REVIEW_INTERNAL_KEY)){
					contentlet.setReviewInterval((String)value);
				}else if(conVariable.equals(Contentlet.DISABLED_WYSIWYG_KEY)){
					contentlet.setDisabledWysiwyg((List<String>)value);
				}else if(conVariable.equals(Contentlet.LOCKED_KEY)){
					contentlet.setLocked((Boolean)value);
				}else if(conVariable.equals(Contentlet.ARCHIVED_KEY)){
					contentlet.setArchived((Boolean)value);
				}else if(conVariable.equals(Contentlet.LIVE_KEY)){
					contentlet.setLive((Boolean)value);
				}else if(conVariable.equals(Contentlet.WORKING_KEY)){
					contentlet.setWorking((Boolean)value);
				}else if(conVariable.equals(Contentlet.MOD_DATE_KEY)){
					contentlet.setModDate((Date)value);
				}else if(conVariable.equals(Contentlet.MOD_USER_KEY)){
					contentlet.setModUser((String)value);
				}else if(conVariable.equals(Contentlet.OWNER_KEY)){
					contentlet.setOwner((String)value);
				}else if(conVariable.equals(Contentlet.IDENTIFIER_KEY)){
					contentlet.setIdentifier((String)value);
				}else if(conVariable.equals(Contentlet.SORT_ORDER_KEY)){
					contentlet.setSortOrder((Long)value);
				}else if(conVariable.equals(Contentlet.HOST_KEY)){
					contentlet.setHost((String)value);
				}else if(conVariable.equals(Contentlet.FOLDER_KEY)){
					contentlet.setFolder((String)value);
				}else if(velFieldmap.get(conVariable) != null){
					Field field = velFieldmap.get(conVariable);
					if(isFieldTypeString(field)) //|| field.getFieldType().equals(Field.FieldType.BINARY.toString()))
					{
						if(checkIsUnique && field.isUnique())
						{
							String dataType = (field.getFieldContentlet() != null) ? field.getFieldContentlet().replaceAll("[0-9]*", "") : "";
							value = value + " (COPY)";
						}
						contentlet.setStringProperty(conVariable, value != null ? (String)value : null);
					}else if(isFieldTypeBoolean(field)){
						contentlet.setBoolProperty(conVariable, value != null ? (Boolean)value : null);
					}else if(isFieldTypeFloat(field)){
						contentlet.setFloatProperty(conVariable, value != null ? (Float)value : null);
					}else if(isFieldTypeDate(field)){
						contentlet.setDateProperty(conVariable,value != null ? (Date)value : null);
					}else if(isFieldTypeLong(field)){
						contentlet.setLongProperty(conVariable,value != null ? (Long)value : null);
					}else if(isFieldTypeBinary(field)){
						contentlet.setBinary(conVariable,(java.io.File)value);
					}
				}else{
					Logger.debug(this,"Value " + value + " in map cannot be set to contentlet");
				}
			}catch (ClassCastException cce) {
				Logger.error(this,"Value in map cannot be set to contentlet", cce);
			} catch (IOException ioe) {
				Logger.error(this,"IO Error in copying Binary File object ", ioe);
			}
		}
	}

	public List<Contentlet> find(Category category, long languageId,boolean live,String orderBy,User user, boolean respectFrontendRoles) throws DotDataException,DotContentletStateException, DotSecurityException {
		List<Category> cats  = new ArrayList<Category>();
		return find(cats,languageId, live, orderBy, user, respectFrontendRoles);
	}

	public List<Contentlet> find(List<Category> categories,long languageId, boolean live, String orderBy, User user, boolean respectFrontendRoles)	throws DotDataException, DotContentletStateException,DotSecurityException {
		if(categories == null || categories.size() < 1)
			return new ArrayList<Contentlet>();
		StringBuffer buffy = new StringBuffer();
		buffy.append("+type:content +deleted:false");
		if(live)
			buffy.append(" +live:true");
		else
			buffy.append(" +working:true");
		if(languageId > 0)
			buffy.append(" +languageId:" + languageId);
		for (Category category : categories) {
			buffy.append(" +c" + category.getInode() + "c:on");
		}
		try {
			return search(buffy.toString(), 0, -1, orderBy, user, respectFrontendRoles);
		} catch (ParseException pe) {
			Logger.error(this,"Unable to search for contentlets" ,pe);
			throw new DotContentletStateException("Unable to search for contentlets", pe);
		}
	}

	public void setContentletProperty(Contentlet contentlet,Field field, Object value)throws DotContentletStateException {
		String[] dateFormats = new String[] { "yyyy-MM-dd HH:mm", "d-MMM-yy", "MMM-yy", "MMMM-yy", "d-MMM", "dd-MMM-yyyy", "MM/dd/yyyy hh:mm aa", "MM/dd/yy HH:mm",
				"MM/dd/yyyy HH:mm", "MMMM dd, yyyy", "M/d/y", "M/d", "EEEE, MMMM dd, yyyy", "MM/dd/yyyy",
				"hh:mm:ss aa", "HH:mm:ss", "yyyy-MM-dd"};
		if(contentlet == null){
			throw new DotContentletValidationException("The contentlet must not be null");
		}
		String stInode = contentlet.getStructureInode();
		if(!InodeUtils.isSet(stInode)){
			throw new DotContentletValidationException("The contentlet's structureInode must be set");
		}
		if(field.getFieldType().equals(Field.FieldType.CATEGORY.toString()) || field.getFieldType().equals(Field.FieldType.CATEGORIES_TAB.toString())){

		}else if(fAPI.isElementConstant(field)){
			Logger.debug(this, "Cannot set contentlet field value on field type constant. Value is saved to the field not the contentlet");
		}else if(field.getFieldContentlet().startsWith("text")){
			try{
				contentlet.setStringProperty(field.getVelocityVarName(), (String)value);
			}catch (Exception e) {
				contentlet.setStringProperty(field.getVelocityVarName(),value.toString());
			}
		}else if(field.getFieldContentlet().startsWith("long_text")){
			try{
				contentlet.setStringProperty(field.getVelocityVarName(), (String)value);
			}catch (Exception e) {
				contentlet.setStringProperty(field.getVelocityVarName(),value.toString());
			}
		}else if(field.getFieldContentlet().startsWith("date")){
			if(value instanceof Date){
				contentlet.setDateProperty(field.getVelocityVarName(), (Date)value);
			}else if(value instanceof String){
				try{
					contentlet.setDateProperty(field.getVelocityVarName(),DateUtil.convertDate((String)value, dateFormats));
				}catch (Exception e) {
					throw new DotContentletStateException("Unable to convert string to date " + value);
				}
			}else{
				throw new DotContentletStateException("Date fields must either be of type String or Date");
			}
		}else if(field.getFieldContentlet().startsWith("bool")){
			if(value instanceof Boolean){
				contentlet.setBoolProperty(field.getVelocityVarName(), (Boolean)value);
			}else if(value instanceof String){
				try{
					String auxValue = (String) value;
					Boolean auxBoolean = (auxValue.equalsIgnoreCase("1") || auxValue.equalsIgnoreCase("true") || auxValue.equalsIgnoreCase("t")) ? Boolean.TRUE : Boolean.FALSE;
					contentlet.setBoolProperty(field.getVelocityVarName(), auxBoolean);
				}catch (Exception e) {
					throw new DotContentletStateException("Unable to set string value as a Boolean");
				}
			}else{
				throw new DotContentletStateException("Boolean fields must either be of type String or Boolean");
			}
		}else if(field.getFieldContentlet().startsWith("float")){
			if(value instanceof Number){
				contentlet.setFloatProperty(field.getVelocityVarName(),((Number)value).floatValue());
			}else if(value instanceof String){
				try{
					contentlet.setFloatProperty(field.getVelocityVarName(),new Float((String)value));
				}catch (Exception e) {
					throw new DotContentletStateException("Unable to set string value as a Float");
				}
			}
		}else if(field.getFieldContentlet().startsWith("integer")){
			if(value instanceof Number){
				contentlet.setLongProperty(field.getVelocityVarName(),((Number)value).longValue());
			}else if(value instanceof String){
				try{
					contentlet.setLongProperty(field.getVelocityVarName(),new Long((String)value));
				}catch (Exception e) {
					throw new DotContentletStateException("Unable to set string value as a Long");
				}
			}
			// http://jira.dotmarketing.net/browse/DOTCMS-1073
			// setBinary
			}else if(field.getFieldContentlet().startsWith("binary")){
				try{
					contentlet.setBinary(field.getVelocityVarName(), (java.io.File) value);
				}catch (Exception e) {
					throw new DotContentletStateException("Unable to set binary file Object");
				}
		}else{
			throw new DotContentletStateException("Unable to set value : Unknown field type");
		}
	}

	public void validateContentlet(Contentlet contentlet,List<Category> cats)throws DotContentletValidationException {
		if(contentlet == null){
			throw new DotContentletValidationException("The contentlet must not be null");
		}
		String stInode = contentlet.getStructureInode();
		if(!InodeUtils.isSet(stInode)){
			throw new DotContentletValidationException("The contentlet's structureInode must be set");
		}
		boolean hasError = false;
		DotContentletValidationException cve = new DotContentletValidationException("Contentlet's fields are not valid");
		List<Field> fields = FieldsCache.getFieldsByStructureInode(stInode);
		Map<String, Object> conMap = contentlet.getMap();
		Structure st = StructureCache.getStructureByInode(contentlet.getStructureInode());
		for (Field field : fields) {
			Object o = conMap.get(field.getVelocityVarName());
			if(o != null){
				if(isFieldTypeString(field)){
					if(!(o instanceof String)){
						cve.addBadTypeField(field);
						Logger.error(this,"A text contentlet must be of type String");
					}
				}else if(isFieldTypeDate(field)){
					if(!(o instanceof Date)){
						cve.addBadTypeField(field);
						Logger.error(this,"A date contentlet must be of type Date");
					}
				}else if(isFieldTypeBoolean(field)){
					if(!(o instanceof Boolean)){
						cve.addBadTypeField(field);
						Logger.error(this,"A bool contentlet must be of type Boolean");
					}
				}else if(isFieldTypeFloat(field)){
					if(!(o instanceof Float)){
						cve.addBadTypeField(field);
						Logger.error(this,"A float contentlet must be of type Float");
					}
				}else if(isFieldTypeLong(field)){
					if(!(o instanceof Long || o instanceof Integer)){
						cve.addBadTypeField(field);
						Logger.error(this,"A integer contentlet must be of type Long or Integer");
					}
					//  http://jira.dotmarketing.net/browse/DOTCMS-1073
					//  binary field validation
				}else if(isFieldTypeBinary(field)){
					if(!(o instanceof java.io.File)){
						cve.addBadTypeField(field);
						Logger.error(this,"A binary contentlet field must be of type File");
					}
				}else if(isFieldTypeSystem(field) || isFieldTypeConstant(field)){

				}else{
					Logger.error(this,"Found an unknown field type : This should never happen!!!");
					throw new DotContentletStateException("Unknown field type");
				}
			}
			if (field.isRequired()) {
				if(o instanceof String){
					String s1 = (String)o;
					if(!UtilMethods.isSet(s1.trim())) {
						cve.addRequiredField(field);
						hasError = true;
						continue;
					}
				}
				else if( field.getFieldType().equals(Field.FieldType.CATEGORY.toString()) ) {
					if( cats == null || cats.size() == 0 ) {
						cve.addRequiredField(field);
						hasError = true;
						continue;
					}
					try {
						User systemUser = APILocator.getUserAPI().getSystemUser();
						if (field.getFieldType().equals(Field.FieldType.CATEGORY.toString())) {
							CategoryAPI catAPI = APILocator.getCategoryAPI();
							Category baseCat = catAPI.find(field.getValues(), systemUser, false);
							List<Category> childrenCats = catAPI.getAllChildren(baseCat, systemUser, false);
							boolean found = false;
							for(Category cat : childrenCats) {
								for(Category passedCat : cats) {
									try {
										if(passedCat.getInode().equalsIgnoreCase(cat.getInode()))
											found = true;
									} catch (NumberFormatException e) { }
								}
							}
							if(!found) {
								cve.addRequiredField(field);
								hasError = true;
								continue;
							}
						}
					} catch (DotDataException e) {
						throw new DotContentletValidationException("Unable to validate a category field: " + field.getVelocityVarName(), e);
					} catch (DotSecurityException e) {
						throw new DotContentletValidationException("Unable to validate a category field: " + field.getVelocityVarName(), e);
					}
				} else if (field.getFieldType().equals(Field.FieldType.HOST_OR_FOLDER.toString())) {
					if (!UtilMethods.isSet(contentlet.getHost()) && !UtilMethods.isSet(contentlet.getFolder())) {
						cve.addRequiredField(field);
						hasError = true;
						continue;
					}
				} else if(!UtilMethods.isSet(o) && (st.getStructureType() != (Structure.STRUCTURE_TYPE_FORM))) {
					cve.addRequiredField(field);
					hasError = true;
					continue;
				}
				if(field.getFieldType().equals(Field.FieldType.IMAGE.toString()) || field.getFieldType().equals(Field.FieldType.FILE.toString())){
					if(o instanceof Number){
						Number n = (Number)o;
						if(n.longValue() == 0){
							cve.addRequiredField(field);
							hasError = true;
							continue;
						}
					}else if(o instanceof String){
						String s = (String)o;
						if(s.trim().equals("0")){
							cve.addRequiredField(field);
							hasError = true;
							continue;
						}
					}
					//WYSIWYG patch for blank content
				}else if(field.getFieldType().equals(Field.FieldType.WYSIWYG.toString())){
					if(o instanceof String){
						String s = (String)o;
						if (s.trim().toLowerCase().equals("<br>")){
							cve.addRequiredField(field);
							hasError = true;
							continue;
						}
					}
				}
			}
			if(field.isUnique()){
				try{
					StringBuilder buffy = new StringBuilder();

					buffy.append(" +(live:true working:true)");
					buffy.append(" +structureInode:" + contentlet.getStructureInode());
					buffy.append(" +languageId:" + contentlet.getLanguageId());
					buffy.append(" +(working:true live:true)");
					if(UtilMethods.isSet(contentlet.getIdentifier())){
					   buffy.append(" -(identifier:" + contentlet.getIdentifier() + ")");
					}
					buffy.append(" +" + contentlet.getStructure().getVelocityVarName() + "." + field.getVelocityVarName() + ":\"" + LuceneUtils.escape(getFieldValue(contentlet, field).toString()) + "\"");
					List<ContentletSearch> contentlets = new ArrayList<ContentletSearch>();
					try {
						contentlets = searchIndex(buffy.toString(), -1, 0, "inode", APILocator.getUserAPI().getSystemUser(), false);
					} catch (ParseException e) {
						Logger.error(this, e.getMessage(),e);
						throw new DotContentletValidationException(e.getMessage(),e);
					} catch (DotSecurityException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					int size = contentlets.size();
					if(size > 0 && !hasError){

						Boolean unique = true;
						for (ContentletSearch contentletSearch : contentlets) {
							Contentlet c = conFac.find(contentletSearch.getInode());
							Map<String, Object> cMap = c.getMap();
							Object obj = cMap.get(field.getVelocityVarName());

							if(((String) obj).equals(((String) o))) { //DOTCMS-7275
								unique = false;
								break;
							}

						}

						if(!unique) {
							if(UtilMethods.isSet(contentlet.getIdentifier())){//DOTCMS-5409
								Iterator<ContentletSearch> contentletsIter = contentlets.iterator();
								while (contentletsIter.hasNext()) {
									ContentletSearch cont = (ContentletSearch) contentletsIter.next();

									if(!contentlet.getIdentifier().equalsIgnoreCase(cont.getIdentifier()))
									{
										cve.addUniqueField(field);
										hasError = true;
										break;
									}

								}
							}else{
								cve.addUniqueField(field);
								hasError = true;
								break;
							}
						}

					}
				}catch (DotDataException e) {
					Logger.error(this,"Unable to get contentlets for structure: " + contentlet.getStructure().getName() ,e);
				}

			}
			String dataType = (field.getFieldContentlet() != null) ? field.getFieldContentlet().replaceAll("[0-9]*", "") : "";
			if (UtilMethods.isSet(o) && dataType.equals("text")) {
				String s = "";
				try{
					s = (String)o;
				}catch (Exception e) {
					Logger.error(this,"Unable to get string value for text field in contentlet",e);
					continue;
				}
				if (s.length() > 255) {
					hasError = true;
					cve.addMaxLengthField(field);
					continue;
				}
			}
			String regext = field.getRegexCheck();
			if (UtilMethods.isSet(regext)) {
				if (UtilMethods.isSet(o)) {
					if(o instanceof Number){
						Number n = (Number)o;
						String s = n.toString();
						boolean match = Pattern.matches(regext, s);
						if (!match) {
							hasError = true;
							cve.addPatternField(field);
							continue;
						}
					}else if(o instanceof String && UtilMethods.isSet(((String)o).trim())){
						String s = ((String)o).trim();
						boolean match = Pattern.matches(regext, s);
						if (!match) {
							hasError = true;
							cve.addPatternField(field);
							continue;
						}
					}
				}
			}
		}
		if(hasError){
			throw cve;
		}
	}

	public void validateContentlet(Contentlet contentlet,Map<Relationship, List<Contentlet>> contentRelationships,List<Category> cats)throws DotContentletValidationException {
		Structure st = StructureCache.getStructureByInode(contentlet.getStructureInode());
		ContentletRelationships relationshipsData = new ContentletRelationships(contentlet);
		List<ContentletRelationshipRecords> relationshipsRecords = new ArrayList<ContentletRelationshipRecords> ();
		relationshipsData.setRelationshipsRecords(relationshipsRecords);
		for(Entry<Relationship, List<Contentlet>> relEntry : contentRelationships.entrySet()) {
			Relationship relationship = (Relationship) relEntry.getKey();
			boolean hasParent = RelationshipFactory.isParentOfTheRelationship(relationship, st);
			ContentletRelationshipRecords records = relationshipsData.new ContentletRelationshipRecords(relationship, hasParent);
			records.setRecords(relEntry.getValue());
		}
		validateContentlet(contentlet, relationshipsData, cats);
	}

	public void validateContentlet(Contentlet contentlet, ContentletRelationships contentRelationships, List<Category> cats)throws DotContentletValidationException {
		DotContentletValidationException cve = new DotContentletValidationException("Contentlet's fields are not valid");
		boolean hasError = false;
		String stInode = contentlet.getStructureInode();
		if(!InodeUtils.isSet(stInode)){
			throw new DotContentletValidationException("The contentlet's structureInode must be set");
		}
		try{
			validateContentlet(contentlet,cats);
		}catch (DotContentletValidationException ve) {
			cve = ve;
			hasError = true;
		}
		if( contentRelationships != null ) {
			List<ContentletRelationshipRecords> records = contentRelationships.getRelationshipsRecords();
			for (ContentletRelationshipRecords cr : records) {
				Relationship rel = cr.getRelationship();
				List<Contentlet> cons = cr.getRecords();
				if(cons == null)
					cons = new ArrayList<Contentlet>();
				//if i am the parent
				if(rel.getParentStructureInode().equalsIgnoreCase(stInode)){
					if(rel.isChildRequired() && cons.isEmpty()){
						hasError = true;
						cve.addRequiredRelationship(rel, cons);
					}
					for(Contentlet con : cons){
						if(!con.getStructureInode().equalsIgnoreCase(rel.getChildStructureInode())){
							hasError = true;
							cve.addInvalidContentRelationship(rel, cons);
						}
					}
				}else if(rel.getChildStructureInode().equalsIgnoreCase(stInode)){
					if(rel.isParentRequired() && cons.isEmpty()){
						hasError = true;
						cve.addRequiredRelationship(rel, cons);
					}
					for(Contentlet con : cons){
						if(!con.getStructureInode().equalsIgnoreCase(rel.getParentStructureInode())){
							hasError = true;
							cve.addInvalidContentRelationship(rel, cons);
						}
					}
				}else{
					hasError = true;
					cve.addBadRelationship(rel, cons);
				}
			}
		}
		if(hasError){
			throw cve;
		}
	}

	public boolean isFieldTypeBoolean(Field field) {
		if(field.getFieldContentlet().startsWith("bool")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeDate(Field field) {
		if(field.getFieldContentlet().startsWith("date")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeFloat(Field field) {
		if(field.getFieldContentlet().startsWith("float")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeLong(Field field) {
		if(field.getFieldContentlet().startsWith("integer")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeString(Field field) {
		if(field.getFieldContentlet().startsWith("text")){
			return true;
		}
		return false;
	}

    //	http://jira.dotmarketing.net/browse/DOTCMS-1073
	public boolean isFieldTypeBinary(Field field) {
		if(field.getFieldContentlet().startsWith("binary")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeSystem(Field field) {
		if(field.getFieldContentlet().startsWith("system")){
			return true;
		}
		return false;
	}

	public boolean isFieldTypeConstant(Field field) {
		if(field.getFieldContentlet().startsWith("constant")){
			return true;
		}
		return false;
	}




	/* (non-Javadoc)
	 * @see com.dotmarketing.portlets.contentlet.business.ContentletAPI#convertContentletToFatContentlet(com.dotmarketing.portlets.contentlet.model.Contentlet, com.dotmarketing.portlets.contentlet.business.Contentlet)
	 */
	public com.dotmarketing.portlets.contentlet.business.Contentlet convertContentletToFatContentlet(
			Contentlet cont,
			com.dotmarketing.portlets.contentlet.business.Contentlet fatty)
	throws DotDataException {
		return conFac.convertContentletToFatContentlet(cont, fatty);
	}

	/* (non-Javadoc)
	 * @see com.dotmarketing.portlets.contentlet.business.ContentletAPI#convertFatContentletToContentlet(com.dotmarketing.portlets.contentlet.business.Contentlet)
	 */
	public Contentlet convertFatContentletToContentlet(
			com.dotmarketing.portlets.contentlet.business.Contentlet fatty)
	throws DotDataException {
		return conFac.convertFatContentletToContentlet(fatty);
	}

	private Contentlet findWorkingContentlet(Contentlet content)throws DotSecurityException, DotDataException, DotContentletStateException{
		if(content != null && InodeUtils.isSet(content.getInode()))
			throw new DotContentletStateException("Contentlet must not exist already");
		Contentlet con = null;
		List<Contentlet> workingCons = new ArrayList<Contentlet>();
		if(InodeUtils.isSet(content.getIdentifier())){
			workingCons = conFac.findContentletsByIdentifier(content.getIdentifier(), false, content.getLanguageId());
		}
		if(workingCons.size() > 0)
			con = workingCons.get(0);
		if(workingCons.size()>1)
			Logger.warn(this, "Multiple working contentlets found for identifier:" + content.getIdentifier() + " with languageid:" + content.getLanguageId() + " returning the lastest modified.");
		return con;
	}

	private Map<Relationship, List<Contentlet>> findContentRelationships(Contentlet contentlet) throws DotDataException, DotSecurityException{
		Map<Relationship, List<Contentlet>> contentRelationships = new HashMap<Relationship, List<Contentlet>>();
		if(contentlet == null)
			return contentRelationships;
		List<Relationship> rels = RelationshipFactory.getAllRelationshipsByStructure(contentlet.getStructure());
		for (Relationship r : rels) {
			if(!contentRelationships.containsKey(r)){
				contentRelationships.put(r, new ArrayList<Contentlet>());
			}
			List<Contentlet> cons = getRelatedContent(contentlet, r, APILocator.getUserAPI().getSystemUser(), true);
			for (Contentlet c : cons) {
				List<Contentlet> l = contentRelationships.get(r);
				l.add(c);
			}
		}
		return contentRelationships;
	}

	public int deleteOldContent(Date deleteFrom, int offset) throws DotDataException {
		int results = 0;
		if(deleteFrom == null){
			throw new DotDataException("Date to delete from must not be null");
		}
		results = conFac.deleteOldContent(deleteFrom, offset);
		return results;
	}

	public List<String> findFieldValues(String structureInode, Field field, User user, boolean respectFrontEndRoles) throws DotDataException {
		List<String> result = new ArrayList<String>();

		List<Contentlet> contentlets;
		if (field.isIndexed()) {
			contentlets = new ArrayList<Contentlet>();
			List<Contentlet> tempContentlets = new ArrayList<Contentlet>();
			int limit = 500;

			StringBuilder query = new StringBuilder("+deleted:false +live:true +structureInode:" + structureInode);

			try {
				tempContentlets = search(query.toString(), limit, 0, field.getFieldContentlet(), user, respectFrontEndRoles, PermissionAPI.PERMISSION_READ);
				if (0 < tempContentlets.size())
					contentlets.addAll(tempContentlets);

				for (int offset = limit; 0 < tempContentlets.size(); offset+=limit) {
					tempContentlets = search(query.toString(), limit, offset, field.getFieldContentlet(), user, respectFrontEndRoles, PermissionAPI.PERMISSION_READ);
					if (0 < tempContentlets.size())
						contentlets.addAll(tempContentlets);
				}
			} catch (Exception e) {
				Logger.debug(this, e.toString());
			}
		} else {
			contentlets = conFac.findContentletsWithFieldValue(structureInode, field);
			try {
				contentlets = perAPI.filterCollection(contentlets, PermissionAPI.PERMISSION_READ, respectFrontEndRoles, user);
			} catch (Exception e) {
				Logger.debug(this, e.toString());
			}
		}

		String value;
		for (Contentlet contentlet: contentlets) {
			try {
				value = null;
				if (field.getFieldType().equals(Field.DataType.BOOL))
					value = "" + contentlet.getBoolProperty(field.getVelocityVarName());
				else if (field.getFieldType().equals(Field.DataType.DATE))
					value = "" + contentlet.getDateProperty(field.getVelocityVarName());
				else if (field.getFieldType().equals(Field.DataType.FLOAT))
					value = "" + contentlet.getFloatProperty(field.getVelocityVarName());
				else if (field.getFieldType().equals(Field.DataType.INTEGER))
					value = "" + contentlet.getLongProperty(field.getVelocityVarName());
				else if (field.getFieldType().equals(Field.DataType.LONG_TEXT))
					value = contentlet.getStringProperty(field.getVelocityVarName());
				else
					value = contentlet.getStringProperty(field.getVelocityVarName());

				if (UtilMethods.isSet(value))
					result.add(value);
			} catch (Exception e) {
				Logger.debug(this, e.toString());
			}
		}

		return result;
	}

	// jira.dotmarketing.net/browse/DOTCMS-1073
	private void deleteBinaryFiles(List<Contentlet> contentlets,Field field) {

			Iterator itr = contentlets.iterator();

			while(itr.hasNext()){
				Contentlet con = (Contentlet)itr.next();
				String inode =  con.getInode();

				// To delete binary files
				String contentletAssetPath = FileFactory.getRealAssetPath()
											+ java.io.File.separator
											+ inode.charAt(0)
											+ java.io.File.separator
											+ inode.charAt(1)
											+ java.io.File.separator
											+ inode;

				if(field != null){
					contentletAssetPath = contentletAssetPath
											+ java.io.File.separator
											+ field.getVelocityVarName();
				}

				// To delete resized images
				String contentletAssetCachePath = FileFactory.getRealAssetPath()
								+ java.io.File.separator
								+ "cache"
								+ java.io.File.separator
								+ inode.charAt(0)
								+ java.io.File.separator
								+ inode.charAt(1)
								+ java.io.File.separator
								+ inode;

				if(field != null){
				contentletAssetCachePath = contentletAssetCachePath
								+ java.io.File.separator
								+ field.getVelocityVarName();
				}


				FileUtil.deltree(new java.io.File(contentletAssetPath));

				FileUtil.deltree(new java.io.File(contentletAssetCachePath));

			}

		}

	//http://jira.dotmarketing.net/browse/DOTCMS-2178
	public java.io.File getBinaryFile(String contentletInode, String velocityVariableName,User user) throws DotDataException,DotSecurityException {

		Logger.debug(this,"Retrieving binary file name : getBinaryFileName()." );

		Contentlet con = conFac.find(contentletInode);

		if(!perAPI.doesUserHavePermission(con,PermissionAPI.PERMISSION_READ,user))
			throw new DotSecurityException("Unauthorized Access");


		java.io.File binaryFile = null ;

		try{
		java.io.File binaryFilefolder = new java.io.File(FileFactory.getRealAssetPath()
				+ java.io.File.separator
				+ contentletInode.charAt(0)
				+ java.io.File.separator
				+ contentletInode.charAt(1)
				+ java.io.File.separator
				+ contentletInode
				+ java.io.File.separator
				+ velocityVariableName);
				if(binaryFilefolder.exists()){
				java.io.File[] files = binaryFilefolder.listFiles(new BinaryFileFilter());
				if(files.length > 0){
				binaryFile = files[0];
				}
				}
		}catch(Exception e){
			Logger.error(this,"Error occured while retrieving binary file name : getBinaryFileName(). ContentletInode : "+contentletInode+"  velocityVaribleName : "+velocityVariableName );
			throw new DotDataException("File System error.");
		}
		return binaryFile;
	}

	public long contentletCount() throws DotDataException {
		return conFac.contentletCount();
	}

	public long contentletIdentifierCount() throws DotDataException {
		return conFac.contentletIdentifierCount();
	}

	public List<Map<String, Serializable>> DBSearch(Query query, User user,boolean respectFrontendRoles) throws ValidationException,DotDataException {
		List<Field> fields = FieldsCache.getFieldsByStructureVariableName(query.getFromClause());
		if(fields == null || fields.size() < 1){
			throw new ValidationException("No Fields found for Content");
		}
//		return conFac.DBSearch(query, fields, fields.get(0).getStructureInode());
		Map<String, String> dbColToObjectAttribute = new HashMap<String, String>();
		for (Field field : fields) {
			dbColToObjectAttribute.put(field.getFieldContentlet(), field.getVelocityVarName());
		}

		String title = "inode";
		for (Field f : fields) {
			if(f.isListed()){
				title = f.getFieldContentlet();
				break;
			}
		}
		if(UtilMethods.isSet(query.getSelectAttributes())){

			if(!query.getSelectAttributes().contains(title)){
				query.getSelectAttributes().add(title);
			}
		}else{
			List<String> atts = new ArrayList<String>();
			atts.add("*");
			atts.add(title + " as " + QueryResult.CMIS_TITLE);
			query.setSelectAttributes(atts);
		}

		return QueryUtil.DBSearch(query, dbColToObjectAttribute, "structure_inode = '" + fields.get(0).getStructureInode() + "'", user, true,respectFrontendRoles);
	}

	private Contentlet copyContentlet(Contentlet contentlet, Host host, Folder folder, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, DotContentletStateException {

		if (user == null) {
			throw new DotSecurityException("A user must be specified.");
		}

		if (!perAPI.doesUserHavePermission(contentlet, PermissionAPI.PERMISSION_READ, user, respectFrontendRoles)) {
			throw new DotSecurityException("You don't have permission to read the source file.");
		}

		// gets the new information for the template from the request object
		Contentlet newContentlet = new Contentlet();
		newContentlet.setStructureInode(contentlet.getStructureInode());
		copyProperties(newContentlet, contentlet.getMap(),true);
		newContentlet.setLocked(false);
		newContentlet.setLive(contentlet.isLive());
		newContentlet.setInode("");
		newContentlet.setIdentifier("");
		newContentlet.setArchived(false);
		newContentlet.setHost(host != null?host.getIdentifier(): contentlet.getHost());
		newContentlet.setFolder(folder != null?folder.getInode(): null);
		newContentlet.setLowIndexPriority(contentlet.isLowIndexPriority());

		List <Field> fields = FieldsCache.getFieldsByStructureInode(contentlet.getStructureInode());
		java.io.File srcFile;
		java.io.File destFile = new java.io.File(Config.CONTEXT.getRealPath(com.dotmarketing.util.Constants.TEMP_BINARY_PATH) + java.io.File.separator + user.getUserId());
		if (!destFile.exists())
			destFile.mkdirs();

		String fieldValue;
	    for (Field tempField: fields) {
			if (tempField.getFieldType().equals(Field.FieldType.BINARY.toString())) {
				fieldValue = "";
				try {
					srcFile = getBinaryFile(contentlet.getInode(), tempField.getVelocityVarName(), user);
					if(srcFile != null) {
						fieldValue = srcFile.getName();
						destFile = new java.io.File(Config.CONTEXT.getRealPath(com.dotmarketing.util.Constants.TEMP_BINARY_PATH) + java.io.File.separator + user.getUserId() + java.io.File.separator + fieldValue);
						if (!destFile.exists())
							destFile.createNewFile();

						FileUtils.copyFile(srcFile, destFile);
						newContentlet.setBinary(tempField.getVelocityVarName(), destFile);
					}
				} catch (Exception e) {
					throw new DotDataException("Error copying binary file: '" + fieldValue + "'");
				}
			}

			if (tempField.getFieldType().equals(Field.FieldType.HOST_OR_FOLDER.toString())) {
				if (folder != null || host != null){
					newContentlet.setStringProperty(tempField.getVelocityVarName(), folder != null?folder.getInode():host.getIdentifier());
				}else{
					if(contentlet.getFolder().equals(FolderFactory.getSystemFolder().getInode())){
						newContentlet.setStringProperty(tempField.getVelocityVarName(), contentlet.getFolder());
					}else{
						newContentlet.setStringProperty(tempField.getVelocityVarName(), contentlet.getHost());
					}
				}
			}
		}

		List<Category> parentCats = catAPI.getParents(contentlet, false, user, respectFrontendRoles);
		ContentletRelationships cr = getAllRelationships(contentlet);
		List<ContentletRelationshipRecords> rr = cr.getRelationshipsRecords();
		Map<Relationship, List<Contentlet>> rels = new HashMap<Relationship, List<Contentlet>>();
		for (ContentletRelationshipRecords crr : rr) {
			rels.put(crr.getRelationship(), crr.getRecords());
		}

		newContentlet = checkin(newContentlet, rels, parentCats, perAPI.getPermissions(contentlet), user, respectFrontendRoles);

	    perAPI.copyPermissions(contentlet, newContentlet);

		return newContentlet;
	}

	public Contentlet copyContentlet(Contentlet contentlet, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, DotContentletStateException {
		HostAPI hostAPI = APILocator.getHostAPI();
		FolderAPI folderAPI = APILocator.getFolderAPI();

		String hostIdentfier = contentlet.getHost();
		String folderIdentifier = contentlet.getFolder();

		Host host = hostAPI.find(hostIdentfier, user, respectFrontendRoles);
		Folder folder = folderAPI.find(folderIdentifier);

		return copyContentlet(contentlet, host, folder, user, respectFrontendRoles);
	}

	public Contentlet copyContentlet(Contentlet contentlet, Host host, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, DotContentletStateException {
		return copyContentlet(contentlet, host, null, user, respectFrontendRoles);
	}

	public Contentlet copyContentlet(Contentlet contentlet, Folder folder, User user, boolean respectFrontendRoles) throws DotDataException, DotSecurityException, DotContentletStateException {
		return copyContentlet(contentlet, null, folder, user, respectFrontendRoles);
	}

	private boolean hasAHostField(String structureInode) {
		List<Field> fields = FieldsCache.getFieldsByStructureInode(structureInode);
		for(Field f : fields) {
			if(f.getFieldType().equals("host or folder"))
				return true;
		}
		return false;
	}

	private boolean hasAHostFieldSet(String structureInode, Contentlet contentlet) {
		List<Field> fields = FieldsCache.getFieldsByStructureInode(structureInode);
		for(Field f : fields) {
			if(f.getFieldType().equals("host or folder") && UtilMethods.isSet(getFieldValue(contentlet, f))){
				return true;
			}
		}
		return false;
	}

	public boolean isInodeIndexed(String inode) {
		if(!UtilMethods.isSet(inode)){
			Logger.warn(this, "Requested Inode is not indexed because Inode is not set");
		}
		LuceneHits lc;
		boolean found = false;
		int counter = 0;
		while(counter < 300){
			try {
				lc = conFac.indexSearch("+inode:" + inode, 0, 0, "modDate");
			} catch (ParseException e) {
				Logger.error(ContentletAPIImpl.class,e.getMessage(),e);
				return false;
			}
			if(lc.getTotal() > 0){
				found = true;
				return true;
			}
			try{
				Thread.sleep(100);
			}catch (Exception e) {
				Logger.debug(this, "Cannot sleep : ", e);
			}
			counter++;
		}
		return found;
	}

	public boolean isInodeIndexed(String inode, int secondsToWait) {
		LuceneHits lc;
		boolean found = false;
		int counter = 0;
		while(counter <= (secondsToWait / 10)){
			try {
				lc = conFac.indexSearch("+inode:" + inode, 0, 0, "modDate");
			} catch (ParseException e) {
				Logger.error(ContentletAPIImpl.class,e.getMessage(),e);
				return false;
			}
			if(lc.getTotal() > 0){
				found = true;
				return true;
			}
			try{
				Thread.sleep(100);
			}catch (Exception e) {
				Logger.debug(this, "Cannot sleep : ", e);
			}
			counter++;
		}
		return found;
	}

	public void UpdateContentWithSystemHost(String hostIdentifier)throws DotDataException {
		conFac.UpdateContentWithSystemHost(hostIdentifier);
	}

    public void removeUserReferences(String userId)throws DotDataException {
		conFac.removeUserReferences(userId);
	}

	public String getUrlMapForContentlet(Contentlet contentlet, User user, boolean respectFrontendRoles) throws DotSecurityException, DotDataException {
		String result = null;

		if (InodeUtils.isSet(contentlet.getStructureInode())) {
			Structure structure = StructureCache.getStructureByInode(contentlet.getStructureInode());
			if (UtilMethods.isSet(structure.getUrlMapPattern())) {
				List<RegExMatch> matches = RegEX.find(structure.getUrlMapPattern(), "({[^{}]+})");
				String urlMapField;
				String urlMapFieldValue;
				result = structure.getUrlMapPattern();
				for (RegExMatch match: matches) {
					urlMapField = match.getMatch();
					urlMapFieldValue = contentlet.getStringProperty(urlMapField.substring(1, (urlMapField.length() - 1)));
					urlMapField = urlMapField.replaceFirst("\\{", "\\\\{");
					urlMapField = urlMapField.replaceFirst("\\}", "\\\\}");
					result = result.replaceAll(urlMapField, urlMapFieldValue);
				}
			}

			Host host = APILocator.getHostAPI().find(contentlet.getHost(), user, respectFrontendRoles);
			if ((host != null) && !host.isSystemHost() && ! respectFrontendRoles) {
				result = result + "?host_id=" + host.getIdentifier();
			}
		}

		return result;
	}

	public Contentlet saveDraft(Contentlet contentlet, Map<Relationship, List<Contentlet>> contentRelationships, List<Category> cats ,List<Permission> permissions, User user,boolean respectFrontendRoles) throws IllegalArgumentException,DotDataException,DotSecurityException, DotContentletStateException, DotContentletValidationException{
		if(contentlet.getInode().equals(""))
			throw new DotContentletStateException(CAN_T_CHANGE_STATE_OF_CHECKED_OUT_CONTENT);

		//get the latest and greatest from db
		Contentlet working = conFac.findContentletByIdentifier(contentlet.getIdentifier(), false, contentlet.getLanguageId());

		// never publish draft
		contentlet.setLive(false);

		/*
		 * Only draft if there is a working version that is not live
		 * and always create a new version if the user is different
		 */
		if(! working.isLive() && working.getModUser().equals(contentlet.getModUser())){

			// if we are the latest and greatest and are a draft
			if(working.getInode().equals(contentlet.getInode()) ){

				return checkinWithoutVersioning(contentlet, contentRelationships,
						cats,
						permissions, user, false);

			}
			else{
				String workingInode = working.getInode();
				copyProperties(working, contentlet.getMap());
				working.setInode(workingInode);
				working.setModUser(user.getUserId());
				return checkinWithoutVersioning(working, contentRelationships,
						cats,
						permissions, user, false);
			}
		}



		contentlet.setLive(false);
		contentlet.setInode(null);
		return checkin(contentlet, contentRelationships,
				cats,
				permissions, user, false);






	}


	public void removeFolderReferences(Folder folder)throws DotDataException {
		conFac.removeFolderReferences(folder);
	}
}