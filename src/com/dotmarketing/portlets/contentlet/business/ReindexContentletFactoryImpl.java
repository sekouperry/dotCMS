package com.dotmarketing.portlets.contentlet.business;

import static com.dotmarketing.business.PermissionAPI.PERMISSION_PUBLISH;
import static com.dotmarketing.business.PermissionAPI.PERMISSION_READ;
import static com.dotmarketing.business.PermissionAPI.PERMISSION_WRITE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.lucene.document.Document;
import org.springframework.util.NumberUtils;

import com.dotmarketing.beans.Identifier;
import com.dotmarketing.beans.Permission;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.cache.FieldsCache;
import com.dotmarketing.cache.IdentifierCache;
import com.dotmarketing.cache.StructureCache;
import com.dotmarketing.common.business.journal.DistributedJournalAPI;
import com.dotmarketing.common.db.DotConnect;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.portlets.structure.business.FieldAPI;
import com.dotmarketing.portlets.structure.factories.RelationshipFactory;
import com.dotmarketing.portlets.structure.model.Field;
import com.dotmarketing.portlets.structure.model.Relationship;
import com.dotmarketing.portlets.structure.model.Structure;
import com.dotmarketing.util.DateUtil;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.NumberUtil;
import com.dotmarketing.util.Parameter;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.lucene.LuceneUtils;

/**
 *
 * @author Maru
 * @author Jason Tesser
 * @version 1.7
 */

public class ReindexContentletFactoryImpl extends ReindexContentletFactory {

	private DistributedJournalAPI journalAPI;
	
	public ReindexContentletFactoryImpl() {
		journalAPI = APILocator.getDistributedJournalAPI();
	}
	
	public List<Document> buildDocList(Identifier identifier) throws Exception{
		List<String> dependenciesToReindex = new ArrayList<String>();
		List<Document> docs = getLuceneForContentlet(identifier, dependenciesToReindex);
		for(String dependency : dependenciesToReindex) {
            Identifier id = IdentifierCache.getIdentifierFromIdentifierCache(dependency); 
			docs.addAll(getLuceneForContentlet(id, null));
		}
		return docs;
	}
	
	@SuppressWarnings("unchecked")
	protected ArrayList<Document> getLuceneForContentlet(Identifier identifier,List<String> dependenciesToReindex) throws Exception {
		return getLuceneForContentlet(identifier, dependenciesToReindex, 0);
	}
	
	
	
	@SuppressWarnings("unchecked")
	protected ArrayList<Document> getLuceneForContentlet(Identifier identifier,List<String> dependenciesToReindex, int loop) throws Exception {

		// store original list
		List<String> dependenciesToReindexOrig = null;{
			if(dependenciesToReindex != null){
				dependenciesToReindexOrig = new ArrayList<String>(dependenciesToReindex);
			}
		}
		
		
		ArrayList<Document> docs = new ArrayList<Document>();
		String SQLServerLockingHack = " ";
		if(DbConnectionFactory.getDBType().equals(DbConnectionFactory.MSSQL)){
			SQLServerLockingHack += " WITH (NOLOCK) ";
		}
		String contentletSQL = "select contentlet.*, identifier.host_inode as host from inode, contentlet, identifier" + SQLServerLockingHack + " where contentlet.inode = inode.inode and " +
				"inode.identifier = ? and ( working= " + DbConnectionFactory.getDBTrue() + " or live = " + DbConnectionFactory.getDBTrue() + "  or deleted = " + DbConnectionFactory.getDBTrue() + ") and inode.identifier = identifier.inode";
		
		String categoriesSQL1 = "select category.category_velocity_var_name as cat_velocity_var, f.velocity_var_name as field_vel_name " +
				"from  category, tree, contentlet c, field f " +
				"where c.inode = ? and c.inode = tree.child and c.structure_inode = f.structure_inode " +
				"and f.field_contentlet in ( ";
		
		String categoriesSQL2 = ") " +
		"and tree.parent = category.inode";

		String relationshipsSQL = "select tree.* from tree" + SQLServerLockingHack +  " where parent = ? or child = ? order by tree_order";
		
		DotConnect db = new DotConnect();
		
		//Retrieving contentlet data
		db.setSQL(contentletSQL);
		db.addParam(identifier.getInode());
		ArrayList<HashMap<String, String>> contentlets = db.getResults();
		if(contentlets.size() < 1){
			return docs;
		}
		String structureInode = "";
		try {
			structureInode = contentlets.get(0).get("structure_inode");
		} catch (Exception e) {
			return docs;
		}

		//Calculating related content added and removed to include in the list of dependencies to reindex
		
		Structure st = StructureCache.getStructureByInode(structureInode);
		String structureName=st.getVelocityVarName();
		if(structureName==null){
			structureName="notfound";
		}

		List<Relationship> relationships = RelationshipFactory.getAllRelationshipsByStructure(st);
		
		ContentletAPI conAPI = APILocator.getContentletAPI();
		
		
		db.setSQL(relationshipsSQL);
		db.addParam(identifier.getInode());
		db.addParam(identifier.getInode());
		ArrayList<HashMap<String, String>> relatedContentlets = db.loadResults();

		//Figuring out what related content dependencies needs to be reindex as well
		if(dependenciesToReindex != null) {
			for(Relationship rel : relationships) {
				
				List<Contentlet> oldDocs = new ArrayList <Contentlet>();
				
				String q = "";
				boolean isSameStructRelationship = rel.getParentStructureInode().equalsIgnoreCase(rel.getChildStructureInode());
				
				if(isSameStructRelationship)
					q = "+type:content +(" + rel.getRelationTypeValue() + "-parent:" + identifier.getInode() + " " + 
						rel.getRelationTypeValue() + "-child:" + identifier.getInode() + ") ";
				else
					q = "+type:content +" + rel.getRelationTypeValue() + ":" + identifier.getInode();
				
				oldDocs  = conAPI.search(q,  -1, 0, null, APILocator.getUserAPI().getSystemUser(), false);

				List<String> oldRelatedIds = new ArrayList<String>();
				if(oldDocs.size() > 0) {
					for(Contentlet oldDoc : oldDocs) {
						oldRelatedIds.add(oldDoc.getIdentifier());
					}
				}
				
				List<String> newRelatedIds = new ArrayList<String>();
				for(HashMap<String, String> relatedEntry : relatedContentlets) {
					String childId = relatedEntry.get("child");
					String parentId = relatedEntry.get("parent");
					if(relatedEntry.get("relation_type").equals(rel.getRelationTypeValue())) {
						if(identifier.getInode().equalsIgnoreCase(childId)) {
							newRelatedIds.add(parentId);
							oldRelatedIds.remove(parentId);
						} else {
							newRelatedIds.add(childId);
							oldRelatedIds.remove(childId);
						}
					}
				}
				
				//Taking the disjunction of both collections will give the old list of dependencies that need to be removed from the 
				//re-indexation and the list of new dependencies no re-indexed yet
				dependenciesToReindex.addAll(CollectionUtils.disjunction(oldRelatedIds, newRelatedIds));
			}
		}
		
		List<Field> fields = FieldsCache.getFieldsByStructureInode(structureInode);


		//Processing categories
		String categoriesSQL = categoriesSQL1;
		boolean catFirst = true;
		boolean hasCatField = false;
		for (Field f : fields) {
			if(f.getFieldType().equals(Field.FieldType.CATEGORY.toString())){
				if(!catFirst){
					categoriesSQL += ",";
				}
				categoriesSQL += "'" + f.getFieldContentlet() + "'";
				catFirst = false;
				hasCatField = true;
			}
		}
		categoriesSQL += categoriesSQL2;
		
		/*
		 * Loop over the list of contentlets that we've gotten
		 */
		
		// make sure we have a working version
		Map<String,String> working = new HashMap<String,String>();
		Map<String,String> live = new HashMap<String,String>();
		Map<String,String> deleted = new HashMap<String,String>();
		
		Set<String> langs = new HashSet<String>();
		
		
		for (HashMap<String, String> row : contentlets) {
			if(row.get("language_id") != null){
				langs.add(row.get("language_id"));
			}
			if(DbConnectionFactory.isDBTrue(row.get("working"))){
				working.put(row.get("language_id"), row.get("inode"));
			}
			if(DbConnectionFactory.isDBTrue(row.get("live"))){
				live.put(row.get("language_id"), row.get("inode"));
			}
			if(DbConnectionFactory.isDBTrue(row.get("deleted"))){
				deleted.put(row.get("language_id"), row.get("inode"));
			}
		}
		boolean beenFixed = false;
		for(String k : langs){
			String w = working.get(k);
			String d = deleted.get(k);
			String l = live.get(k);
			if(w==null){
				Logger.error(this.getClass(), "Found a contentlet without a working version id: " + identifier.getInode() + " language id: " + k);
				try{
					if( l!=null ){
						db.setSQL("UPDATE contentlet set working= " + DbConnectionFactory.getDBTrue() + " where inode = ?");
						db.addParam(l);
						db.loadResult();
						beenFixed = true;
					}else if( d!=null ){
						db.setSQL("UPDATE contentlet set working= " + DbConnectionFactory.getDBTrue()+  " where inode = ?");
						db.addParam(d);
						db.loadResult();
						beenFixed=true;
					}
				}
				catch(Exception e){
					Logger.error(this.getClass(), "Error setting a working version:" + e.getMessage());
				}
			}
		}
		
		if(loop < 2 && beenFixed){
			return getLuceneForContentlet(identifier, dependenciesToReindexOrig, ++loop);
		}
		
		
		for (HashMap<String, String> row : contentlets) {

			
			String newContentletInode = row.get("inode");
			//DOTCMS-3232
			String host = row.get("host");
			String folder = row.get("folder");

			Contentlet contentProxyForPermisions = new Contentlet();
			contentProxyForPermisions.setIdentifier(identifier.getInode());
			contentProxyForPermisions.setInode(newContentletInode);
			contentProxyForPermisions.setHost(host);
			contentProxyForPermisions.setFolder(folder);
			contentProxyForPermisions.setStructureInode(structureInode);
			contentProxyForPermisions.setWorking(DbConnectionFactory.isDBTrue(row.get("working")));
			contentProxyForPermisions.setLive(DbConnectionFactory.isDBTrue(row.get("live")));
			contentProxyForPermisions.setLanguageId(Long.parseLong(row.get("language_id")));
			contentProxyForPermisions.setModUser(row.get("mod_user"));
			contentProxyForPermisions.setModDate(DateUtil.convertDate(UtilMethods.isSet(row.get("mod_date")) ? row.get("mod_date") : null, new String[] {"yyyy-MM-dd HH:mm:ss"}));
			
			ArrayList<HashMap<String, String>> categoriesResults = null;
			String categories ="";
			
			if (hasCatField) {
				db = new DotConnect();
				db.setSQL(categoriesSQL);
				db.addParam(newContentletInode);
				categoriesResults = db.getResults();
	
				for (HashMap<String, String> crow : categoriesResults) {
					/**
					 * Categories
					 */
					try {
						String categoryId= crow.get("cat_velocity_var");
						if (UtilMethods.isSet(categoryId)) {
							categories+= " "+ categoryId;
							categoryId="";
						}
					} catch (Exception e) {
					}
	
				}
				categories = categories.trim();
			}
			Document doc = new Document();
			FieldAPI fAPI = APILocator.getFieldAPI();
			
			for (Field f : fields) {
				if (f.getFieldType().equals(Field.FieldType.BINARY.toString())
						|| f.getFieldContentlet() != null && f.getFieldContentlet().startsWith("system_field")) {
					continue;
				}

				try {
					if(fAPI.isElementConstant(f)){
						LuceneUtils.addFieldToLuceneDoc(doc,st.getVelocityVarName() + "." + f.getVelocityVarName(), (f.getValues() == null ? "":f.getValues().toString()), fAPI.isAnalyze(f));
						contentProxyForPermisions.setStringProperty(f.getVelocityVarName(), (f.getValues() == null ? "":f.getValues().toString()));
						continue;
					}
					
					String valueObj = row.get(f.getFieldContentlet());
					if(valueObj == null){
						valueObj = "";
					}
					if (f.getFieldContentlet().startsWith("section_divider")) {
						valueObj = "";
					}

					if (f.getFieldType().equals("date") || f.getFieldType().equals("date_time")
							|| f.getFieldType().equals("time")) {
						String luceneDate = "";
						if (f.getFieldType().equals("date")) {
							luceneDate = LuceneUtils.toLuceneDateWithFormat(valueObj, "yyyy-MM-dd");
							if (UtilMethods.isSet(luceneDate) && !luceneDate.equals(LuceneUtils.ERROR_DATE))
								luceneDate = luceneDate.substring(0, 8);
						} else if (f.getFieldType().equals("date_time")) {
							luceneDate = LuceneUtils.toLuceneDateWithFormat(valueObj, "yyyy-MM-dd HH:mm:ss");
						} else if (f.getFieldType().equals("time")) {
							luceneDate = LuceneUtils.toLuceneTimeWithFormat(valueObj, "yyyy-MM-dd HH:mm:ss");
						}

						LuceneUtils.addFieldToLuceneDoc(doc, st.getVelocityVarName() + "." + f.getVelocityVarName(), luceneDate, fAPI.isAnalyze(f));
						contentProxyForPermisions.setDateProperty(f.getVelocityVarName(), UtilMethods.isSet(valueObj) ? DateUtil.convertDate(valueObj, 
								new String[] { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd" } ): null);
					} else if (f.getFieldType().equals("category")) {
						for (HashMap<String, String> r : categoriesResults) {
							if (f.getVelocityVarName().equals(r.get("field_vel_name"))) {
								LuceneUtils.addFieldToLuceneDoc(doc, st.getVelocityVarName() + "."
										+ f.getVelocityVarName(), r.get("cat_velocity_var"), fAPI.isAnalyze(f));
							}
						}

					} else if (f.getFieldType().equals("checkbox") || f.getFieldType().equals("multi_select")) {
						if (f.getFieldContentlet().startsWith("bool")) {
							LuceneUtils.addFieldToLuceneDoc(doc,
									st.getVelocityVarName() + "." + f.getVelocityVarName(), DbConnectionFactory
											.isDBTrue(valueObj) ? "true" : "false", fAPI.isAnalyze(f));
							contentProxyForPermisions.setBoolProperty(f.getVelocityVarName(), DbConnectionFactory.isDBTrue(valueObj));
						} else {
							LuceneUtils.addFieldToLuceneDoc(doc,
									st.getVelocityVarName() + "." + f.getVelocityVarName(), UtilMethods
											.listToString(valueObj), fAPI.isAnalyze(f));
							contentProxyForPermisions.setStringProperty(f.getVelocityVarName(), UtilMethods.listToString(valueObj));
						}
					} else {
						if (f.getFieldContentlet().startsWith("bool")) {
							LuceneUtils.addFieldToLuceneDoc(doc, st.getVelocityVarName() + "." + f.getVelocityVarName(), 
									DbConnectionFactory.isDBTrue(valueObj) ? "true" : "false", fAPI.isAnalyze(f));
							contentProxyForPermisions.setBoolProperty(f.getVelocityVarName(), DbConnectionFactory.isDBTrue(valueObj));
						} else if (f.getFieldContentlet().startsWith("float")) {
							LuceneUtils.addFieldToLuceneDoc(doc,
									st.getVelocityVarName() + "." + f.getVelocityVarName(), LuceneUtils
											.toLuceneNumber(NumberUtils.parseNumber(valueObj, Float.class)), fAPI
											.isAnalyze(f));
							contentProxyForPermisions.setFloatProperty(f.getVelocityVarName(), (Float)NumberUtils.parseNumber(valueObj, Float.class));
						} else if (f.getFieldContentlet().startsWith("integer")) {
							LuceneUtils.addFieldToLuceneDoc(doc,
									st.getVelocityVarName() + "." + f.getVelocityVarName(), LuceneUtils
									.toLuceneNumber(NumberUtils.parseNumber(valueObj, Long.class)), fAPI
											.isAnalyze(f));
							contentProxyForPermisions.setLongProperty(f.getVelocityVarName(), (Long)NumberUtils.parseNumber(valueObj, Long.class));
						} else {
							LuceneUtils.addFieldToLuceneDoc(doc,
									st.getVelocityVarName() + "." + f.getVelocityVarName(), valueObj.toString(), fAPI
											.isAnalyze(f));
							contentProxyForPermisions.setStringProperty(f.getVelocityVarName(), valueObj.toString());
						}
					}
				} catch (Exception e) {
					Logger.warn(ReindexContentletFactoryImpl.class, "Error indexing field: " + f.getFieldName()
							+ " of contentlet: " + newContentletInode, e);
				}
			}
			// Relationships lucene fields
			for(Relationship rel : relationships) {
				
				boolean isSameStructRelationship = rel.getParentStructureInode().equalsIgnoreCase(rel.getChildStructureInode());
				String propName = rel.getRelationTypeValue();
				String propValues = "";
				
				for(HashMap<String, String> relatedEntry : relatedContentlets) {
					String childId = relatedEntry.get("child");
					String parentId = relatedEntry.get("parent");
					Long order = Long.parseLong(relatedEntry.get("tree_order"));
					if(isSameStructRelationship) {
						propName = identifier.getInode().equals(parentId)?rel.getRelationTypeValue() + "-child":rel.getRelationTypeValue() + "-parent";
					}
					if(relatedEntry.get("relation_type").equals(rel.getRelationTypeValue())) {
						if(identifier.getInode().equalsIgnoreCase(childId)) {
							//Index property value
							//Index property name if the content is on a same structure (same structure child and parent) kind of relationships
							//The we append "-child" at the end of the property to distinguish when indexing the children or the parents
							//side of the relationship
							//checking if there already values saved on the relationship property, if it's true then 
							//appending the new values to the property
							if(doc.get(propName) != null) {
								propValues = doc.get(propName) + parentId + " ";
								doc.removeField(propName);
							} else {
								propValues = parentId + " ";
							}
							LuceneUtils.addFieldToLuceneDoc(doc, propName, propValues, true);
							//Adding the order of the content to be able to search order by the relationship implicit tree order
							LuceneUtils.addFieldToLuceneDoc(doc, rel.getRelationTypeValue() + "-" + parentId + "-order", NumberUtil.pad(order), false);
						} else {
							//Index property value
							//Index property name if the content is on a same structure (same structure child and parent) kind of relationships
							//The we append "-child" at the end of the property to distinguish when indexing the children or the parents
							//side of the relationship
							if(doc.get(propName) != null) {
								propValues = doc.get(propName) + childId + " ";
								doc.removeField(propName);
							} else {
								propValues = childId + " ";
							}
							LuceneUtils.addFieldToLuceneDoc(doc, propName, propValues, true);
							//Adding the order of the content to be able to search order by the relationship implicit tree order
							LuceneUtils.addFieldToLuceneDoc(doc, rel.getRelationTypeValue() + "-" + childId + "-order", NumberUtil.pad(order), false);
						}
					}
				}

			}
			

			// get Permissions
			PermissionAPI permissionAPI = APILocator.getPermissionAPI();
			List<Permission> permissions = permissionAPI.getPermissions(contentProxyForPermisions, false, false, true);
			StringBuffer permissionsSt = new StringBuffer();
			boolean ownerCanRead = false;
			boolean ownerCanWrite = false;
			boolean ownerCanPub = false;
			for (Permission permission : permissions) {
				String str = "P" + permission.getRoleId() + "." + permission.getPermission() + "P ";
				if (permissionsSt.toString().indexOf(str) < 0) {
					permissionsSt.append(str);
				}
				if(APILocator.getRoleAPI().loadCMSOwnerRole().getId().equals(String.valueOf(permission.getRoleId()))){
					if(permission.getPermission() == PERMISSION_READ){
						ownerCanRead = true;
					}else if(permission.getPermission() == PERMISSION_WRITE){
						ownerCanRead = true;
						ownerCanWrite = true;
					}else if(permission.getPermission() == PERMISSION_PUBLISH){
						ownerCanRead = true;
						ownerCanWrite = true;
						ownerCanPub = true;
					}
				}
			}			
			
			if(permissionsSt.length()==0)
			    Logger.warn(this, "empty permissions for inode:"+newContentletInode);

			// Other Properties to show in the listing
			LuceneUtils.addFieldToLuceneDoc(doc, "structureName", structureName, false);
			LuceneUtils.addFieldToLuceneDoc(doc, "inode", newContentletInode, false);
			LuceneUtils.addFieldToLuceneDoc(doc, "type", "content", false);
			LuceneUtils.addFieldToLuceneDoc(doc, "modDate", LuceneUtils.toLuceneDateWithFormat(row.get("mod_date"), "yyyy-MM-dd HH:mm:ss"), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "owner", identifier.getOwner() == null ? "0" : identifier.getOwner(), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "ownerCanRead", Boolean.toString(ownerCanRead), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "ownerCanWrite", Boolean.toString(ownerCanWrite), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "ownerCanPublish", Boolean.toString(ownerCanPub), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "modUser", UtilMethods.webifyString(row.get("mod_user")), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "working", Boolean.toString(Parameter.getBooleanFromString(row.get("working"))), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "live", Boolean.toString(Parameter.getBooleanFromString(row.get("live"))), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "deleted", Boolean.toString(Parameter.getBooleanFromString(row.get("deleted"))), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "locked", Boolean.toString(Parameter.getBooleanFromString(row.get("locked"))), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "languageId", String.valueOf(row.get("language_id")), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "permissions", permissionsSt.toString(), true);
			LuceneUtils.addFieldToLuceneDoc(doc, "identifier", identifier.getInode(), false);
			LuceneUtils.addFieldToLuceneDoc(doc, "conHost", host, false);
			LuceneUtils.addFieldToLuceneDoc(doc, "conFolder", folder, false);
			LuceneUtils.addFieldToLuceneDoc(doc, "categories", categories, true);
			docs.add(doc);
		}
		return docs;

	}
	
}