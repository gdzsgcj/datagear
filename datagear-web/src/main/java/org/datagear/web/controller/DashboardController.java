/*
 * Copyright 2018 datagear.tech. All Rights Reserved.
 */

package org.datagear.web.controller;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.datagear.analysis.DataSetResult;
import org.datagear.analysis.TemplateDashboardWidgetResManager;
import org.datagear.analysis.support.html.HtmlDashboard;
import org.datagear.analysis.support.html.HtmlRenderAttributes;
import org.datagear.analysis.support.html.HtmlRenderContext;
import org.datagear.analysis.support.html.HtmlRenderContext.WebContext;
import org.datagear.analysis.support.html.HtmlTplDashboardWidget;
import org.datagear.analysis.support.html.HtmlTplDashboardWidgetRenderer;
import org.datagear.analysis.support.html.HtmlTplDashboardWidgetRenderer.AddPrefixHtmlTitleHandler;
import org.datagear.management.domain.HtmlTplDashboardWidgetEntity;
import org.datagear.management.domain.User;
import org.datagear.management.service.HtmlChartWidgetEntityService.ChartWidgetSourceContext;
import org.datagear.management.service.HtmlTplDashboardWidgetEntityService;
import org.datagear.persistence.PagingData;
import org.datagear.persistence.PagingQuery;
import org.datagear.util.FileUtil;
import org.datagear.util.IDUtil;
import org.datagear.util.IOUtil;
import org.datagear.util.StringUtil;
import org.datagear.web.OperationMessage;
import org.datagear.web.util.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * 看板控制器。
 * 
 * @author datagear@163.com
 *
 */
@Controller
@RequestMapping("/analysis/dashboard")
public class DashboardController extends AbstractDataAnalysisController
{
	static
	{
		AuthorizationResourceMetas.registerForShare(HtmlTplDashboardWidgetEntity.AUTHORIZATION_RESOURCE_TYPE,
				"dashboard");
	}

	@Autowired
	private HtmlTplDashboardWidgetEntityService htmlTplDashboardWidgetEntityService;

	@Autowired
	private File tempDirectory;

	public DashboardController()
	{
		super();
	}

	public DashboardController(HtmlTplDashboardWidgetEntityService htmlTplDashboardWidgetEntityService,
			File tempDirectory)
	{
		super();
		this.htmlTplDashboardWidgetEntityService = htmlTplDashboardWidgetEntityService;
		this.tempDirectory = tempDirectory;
	}

	public HtmlTplDashboardWidgetEntityService getHtmlTplDashboardWidgetEntityService()
	{
		return htmlTplDashboardWidgetEntityService;
	}

	public void setHtmlTplDashboardWidgetEntityService(
			HtmlTplDashboardWidgetEntityService htmlTplDashboardWidgetEntityService)
	{
		this.htmlTplDashboardWidgetEntityService = htmlTplDashboardWidgetEntityService;
	}

	public File getTempDirectory()
	{
		return tempDirectory;
	}

	public void setTempDirectory(File tempDirectory)
	{
		this.tempDirectory = tempDirectory;
	}

	@RequestMapping("/add")
	public String add(HttpServletRequest request, org.springframework.ui.Model model)
	{
		HtmlTplDashboardWidgetEntity dashboard = new HtmlTplDashboardWidgetEntity();

		dashboard.setTemplate(HtmlTplDashboardWidgetEntity.DEFAULT_TEMPLATE);
		dashboard.setTemplateEncoding(HtmlTplDashboardWidget.DEFAULT_TEMPLATE_ENCODING);

		HtmlTplDashboardWidgetRenderer<HtmlRenderContext> renderer = getHtmlTplDashboardWidgetEntityService()
				.getHtmlTplDashboardWidgetRenderer();

		String templateContent = renderer.simpleTemplateContent(dashboard.getTemplateEncoding());

		model.addAttribute("dashboard", dashboard);
		model.addAttribute("templateContent", templateContent);
		model.addAttribute(KEY_TITLE_MESSAGE_KEY, "dashboard.addDashboard");
		model.addAttribute(KEY_FORM_ACTION, "save");

		return "/analysis/dashboard/dashboard_form";
	}

	@RequestMapping("/edit")
	public String edit(HttpServletRequest request, HttpServletResponse response, org.springframework.ui.Model model,
			@RequestParam("id") String id) throws Exception
	{
		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = this.htmlTplDashboardWidgetEntityService.getByIdForEdit(user, id);

		if (dashboard == null)
			throw new RecordNotFoundException();

		model.addAttribute("dashboard", dashboard);
		readAndSetTemplateContent(dashboard, model);
		model.addAttribute(KEY_TITLE_MESSAGE_KEY, "dashboard.editDashboard");
		model.addAttribute(KEY_FORM_ACTION, "save");

		return "/analysis/dashboard/dashboard_form";
	}

	@RequestMapping(value = "/save", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> save(HttpServletRequest request, HttpServletResponse response,
			HtmlTplDashboardWidgetEntity dashboard, @RequestParam("templateContent") String templateContent)
			throws Exception
	{
		User user = WebUtils.getUser(request, response);

		checkSaveEntity(dashboard);

		boolean save = false;

		if (isEmpty(dashboard.getId()))
		{
			dashboard.setId(IDUtil.randomIdOnTime20());
			dashboard.setCreateUser(user);
			dashboard.setTemplateEncoding(resolveTemplateEncoding(templateContent));
			save = this.htmlTplDashboardWidgetEntityService.add(user, dashboard);
		}
		else
		{
			dashboard.setTemplateEncoding(resolveTemplateEncoding(templateContent));
			save = this.htmlTplDashboardWidgetEntityService.update(user, dashboard);
		}

		if (save)
			saveTemplateContent(dashboard, templateContent);

		Map<String, Object> data = new HashMap<String, Object>();
		data.put("id", dashboard.getId());

		ResponseEntity<OperationMessage> responseEntity = buildOperationMessageSaveSuccessResponseEntity(request);
		responseEntity.getBody().setData(data);

		return responseEntity;
	}

	@RequestMapping(value = "/listResources", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public List<String> listResources(HttpServletRequest request, HttpServletResponse response,
			org.springframework.ui.Model model, @RequestParam("id") String id) throws Exception
	{
		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = this.htmlTplDashboardWidgetEntityService.getById(user, id);

		if (dashboard == null)
			return new ArrayList<String>(0);

		TemplateDashboardWidgetResManager dashboardWidgetResManager = this.htmlTplDashboardWidgetEntityService
				.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

		List<String> resources = dashboardWidgetResManager.listResources(dashboard.getId());

		return resources;
	}

	@RequestMapping(value = "/deleteResource", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public boolean deleteResource(HttpServletRequest request, HttpServletResponse response,
			org.springframework.ui.Model model, @RequestParam("id") String id, @RequestParam("name") String name)
			throws Exception
	{
		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = this.htmlTplDashboardWidgetEntityService.getByIdForEdit(user, id);

		if (dashboard == null)
			return false;

		TemplateDashboardWidgetResManager dashboardWidgetResManager = this.htmlTplDashboardWidgetEntityService
				.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

		dashboardWidgetResManager.delete(id, name);

		return true;
	}

	@RequestMapping(value = "/uploadResourceFile", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public Map<String, Object> uploadResourceFile(HttpServletRequest request, HttpServletResponse response,
			@RequestParam("file") MultipartFile multipartFile) throws Exception
	{
		File tmpDirectory = FileUtil.generateUniqueDirectory(this.tempDirectory);
		String fileName = multipartFile.getOriginalFilename();
		File file = FileUtil.getFile(tmpDirectory, fileName);

		InputStream in = null;
		OutputStream out = null;
		try
		{
			in = multipartFile.getInputStream();
			out = IOUtil.getOutputStream(file);
			IOUtil.write(in, out);
		}
		finally
		{
			IOUtil.close(in);
			IOUtil.close(out);
		}

		String uploadFilePath = FileUtil.getRelativePath(this.tempDirectory, file);

		Map<String, Object> results = new HashMap<String, Object>();
		results.put("uploadFilePath", uploadFilePath);
		results.put("fileName", fileName);

		return results;
	}

	@RequestMapping(value = "/saveResourceFile", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> saveResourceFile(HttpServletRequest request, HttpServletResponse response,
			@RequestParam("id") String id, @RequestParam("resourceFilePath") String resourceFilePath,
			@RequestParam("resourceName") String resourceName) throws Exception
	{
		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = this.htmlTplDashboardWidgetEntityService.getByIdForEdit(user, id);

		if (dashboard == null)
			throw new RecordNotFoundException();

		File uploadFile = FileUtil.getDirectory(this.tempDirectory, resourceFilePath, false);

		if (!uploadFile.exists())
			throw new IllegalInputException();

		TemplateDashboardWidgetResManager dashboardWidgetResManager = this.htmlTplDashboardWidgetEntityService
				.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

		InputStream in = null;
		OutputStream out = null;

		try
		{
			in = IOUtil.getInputStream(uploadFile);
			out = dashboardWidgetResManager.getResourceOutputStream(id, resourceName);

			IOUtil.write(in, out);
		}
		finally
		{
			IOUtil.close(in);
			IOUtil.close(out);
		}

		return buildOperationMessageSaveSuccessResponseEntity(request);
	}

	@RequestMapping("/import")
	public String impt(HttpServletRequest request, org.springframework.ui.Model model)
	{
		return "/analysis/dashboard/dashboard_import";
	}

	@RequestMapping(value = "/uploadImportFile", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public Map<String, Object> uploadImportFile(HttpServletRequest request, HttpServletResponse response,
			@RequestParam("file") MultipartFile multipartFile) throws Exception
	{
		String dashboardFileName = "";
		String template = "";

		File tmpDirectory = FileUtil.generateUniqueDirectory(this.tempDirectory);

		dashboardFileName = tmpDirectory.getName();

		String fileName = multipartFile.getOriginalFilename();

		if (FileUtil.isExtension(fileName, "zip"))
		{
			ZipInputStream in = IOUtil.getZipInputStream(multipartFile.getInputStream());
			try
			{
				IOUtil.unzip(in, tmpDirectory);
			}
			finally
			{
				IOUtil.close(in);
			}

			File[] files = tmpDirectory.listFiles();
			if (files != null)
			{
				for (File file : files)
				{
					if (file.isDirectory())
						continue;

					String name = file.getName();
					if (FileUtil.isExtension(name, "html") || FileUtil.isExtension(name, "htm"))
					{
						template = name;

						if (template.equalsIgnoreCase("index.html") || template.equalsIgnoreCase("index.htm"))
							break;
					}
				}
			}
		}
		else
		{
			File file = FileUtil.getFile(tmpDirectory, fileName);

			InputStream in = null;
			OutputStream out = null;
			try
			{
				in = multipartFile.getInputStream();
				out = IOUtil.getOutputStream(file);
				IOUtil.write(in, out);
			}
			finally
			{
				IOUtil.close(in);
				IOUtil.close(out);
			}

			template = fileName;
		}

		Map<String, Object> results = new HashMap<String, Object>();
		results.put("dashboardFileName", dashboardFileName);
		results.put("template", template);

		return results;
	}

	@RequestMapping(value = "/saveImport", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> saveImport(HttpServletRequest request, HttpServletResponse response,
			@RequestParam("name") String name, @RequestParam("template") String template,
			@RequestParam("dashboardFileName") String dashboardFileName) throws Exception
	{
		File uploadDirectory = FileUtil.getDirectory(this.tempDirectory, dashboardFileName, false);

		if (!uploadDirectory.exists())
			throw new IllegalInputException();

		File templateFile = FileUtil.getFile(uploadDirectory, template);

		if (!templateFile.exists() || templateFile.isDirectory())
			return buildOperationMessageFailResponseEntity(request, HttpStatus.BAD_REQUEST,
					"dashboard.import.templateFileNotExists", template);

		String templateEncoding = resolveTemplateEncoding(templateFile);

		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = new HtmlTplDashboardWidgetEntity();
		dashboard.setTemplate(template);
		dashboard.setTemplateEncoding(templateEncoding);
		dashboard.setName(name);

		checkSaveEntity(dashboard);

		dashboard.setId(IDUtil.randomIdOnTime20());
		dashboard.setCreateUser(user);

		this.htmlTplDashboardWidgetEntityService.add(user, dashboard);

		TemplateDashboardWidgetResManager dashboardWidgetResManager = this.htmlTplDashboardWidgetEntityService
				.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

		dashboardWidgetResManager.copyFrom(dashboard.getId(), uploadDirectory);

		return buildOperationMessageSaveSuccessResponseEntity(request);
	}

	@RequestMapping("/view")
	public String view(HttpServletRequest request, HttpServletResponse response, org.springframework.ui.Model model,
			@RequestParam("id") String id) throws Exception
	{
		User user = WebUtils.getUser(request, response);

		HtmlTplDashboardWidgetEntity dashboard = this.htmlTplDashboardWidgetEntityService.getById(user, id);

		if (dashboard == null)
			throw new RecordNotFoundException();

		model.addAttribute("dashboard", dashboard);
		readAndSetTemplateContent(dashboard, model);
		model.addAttribute(KEY_TITLE_MESSAGE_KEY, "dashboard.viewDashboard");
		model.addAttribute(KEY_READONLY, true);

		return "/analysis/dashboard/dashboard_form";
	}

	@RequestMapping(value = "/delete", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public ResponseEntity<OperationMessage> delete(HttpServletRequest request, HttpServletResponse response,
			@RequestParam("id") String[] ids)
	{
		User user = WebUtils.getUser(request, response);

		for (int i = 0; i < ids.length; i++)
		{
			String id = ids[i];
			this.htmlTplDashboardWidgetEntityService.deleteById(user, id);
		}

		return buildOperationMessageDeleteSuccessResponseEntity(request);
	}

	@RequestMapping("/pagingQuery")
	public String pagingQuery(HttpServletRequest request, HttpServletResponse response,
			org.springframework.ui.Model model)
	{
		User user = WebUtils.getUser(request, response);
		model.addAttribute("currentUser", user);

		model.addAttribute(KEY_TITLE_MESSAGE_KEY, "dashboard.manageDashboard");

		return "/analysis/dashboard/dashboard_grid";
	}

	@RequestMapping(value = "/select")
	public String select(HttpServletRequest request, HttpServletResponse response, org.springframework.ui.Model model)
	{
		model.addAttribute(KEY_TITLE_MESSAGE_KEY, "dashboard.selectDashboard");
		model.addAttribute(KEY_SELECTONLY, true);

		return "/analysis/dashboard/dashboard_grid";
	}

	@RequestMapping(value = "/pagingQueryData", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public PagingData<HtmlTplDashboardWidgetEntity> pagingQueryData(HttpServletRequest request,
			HttpServletResponse response, final org.springframework.ui.Model springModel) throws Exception
	{
		User user = WebUtils.getUser(request, response);

		PagingQuery pagingQuery = getPagingQuery(request);
		String dataFilter = getDataFilterValue(request);

		PagingData<HtmlTplDashboardWidgetEntity> pagingData = this.htmlTplDashboardWidgetEntityService.pagingQuery(user,
				pagingQuery, dataFilter);

		return pagingData;
	}

	/**
	 * 展示看板首页。
	 * 
	 * @param request
	 * @param response
	 * @param model
	 * @param id
	 * @throws Exception
	 */
	@RequestMapping("/show/{id}/")
	public void show(HttpServletRequest request, HttpServletResponse response, org.springframework.ui.Model model,
			@PathVariable("id") String id) throws Exception
	{
		User user = WebUtils.getUser(request, response);
		HtmlTplDashboardWidgetEntity dashboardWidget = this.htmlTplDashboardWidgetEntityService
				.getHtmlTplDashboardWidget(user, id);

		showDashboard(request, response, model, user, dashboardWidget);
	}

	/**
	 * 加载看板资源。
	 * 
	 * @param request
	 * @param response
	 * @param webRequest
	 * @param model
	 * @param id
	 * @throws Exception
	 */
	@RequestMapping("/show/{id}/**/*")
	public void showResource(HttpServletRequest request, HttpServletResponse response, WebRequest webRequest,
			org.springframework.ui.Model model, @PathVariable("id") String id) throws Exception
	{
		User user = WebUtils.getUser(request, response);
		HtmlTplDashboardWidgetEntity entity = this.htmlTplDashboardWidgetEntityService.getById(user, id);

		if (entity == null)
			throw new RecordNotFoundException();
		
		String resName = resolveDashboardResName(request, response, id);

		if (isEmpty(resName) || resName.equals(entity.getTemplate()))
		{
			HtmlTplDashboardWidgetEntity dashboardWidget = this.htmlTplDashboardWidgetEntityService
					.getHtmlTplDashboardWidget(user, id);

			showDashboard(request, response, model, user, dashboardWidget);
		}
		else
		{
			TemplateDashboardWidgetResManager resManager = this.htmlTplDashboardWidgetEntityService
					.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

			if (!resManager.containsResource(id, resName))
				throw new FileNotFoundException(resName);

			long lastModified = resManager.lastModifiedResource(id, resName);
			if (webRequest.checkNotModified(lastModified))
				return;

			InputStream in = resManager.getResourceInputStream(id, resName);
			OutputStream out = response.getOutputStream();

			IOUtil.write(in, out);
		}
	}

	/**
	 * 展示看板。
	 * 
	 * @param request
	 * @param response
	 * @param model
	 * @param id
	 * @throws Exception
	 */
	protected void showDashboard(HttpServletRequest request, HttpServletResponse response,
			org.springframework.ui.Model model,
			User user, HtmlTplDashboardWidgetEntity dashboardWidget) throws Exception
	{
		if (dashboardWidget == null)
			throw new RecordNotFoundException();

		// 确保看板创建用户对看板模板内定义的图表有权限
		ChartWidgetSourceContext.set(new ChartWidgetSourceContext(dashboardWidget.getCreateUser()));

		try
		{
			TemplateDashboardWidgetResManager dashboardWidgetResManager = this.htmlTplDashboardWidgetEntityService
					.getHtmlTplDashboardWidgetRenderer().getTemplateDashboardWidgetResManager();

			String responseEncoding = dashboardWidget.getTemplateEncoding();

			if (StringUtil.isEmpty(responseEncoding))
				responseEncoding = dashboardWidgetResManager.getDefaultEncoding();

			response.setCharacterEncoding(responseEncoding);
			response.setContentType(CONTENT_TYPE_HTML);

			Writer out = response.getWriter();

			HtmlRenderContext renderContext = createHtmlRenderContext(request, createWebContext(request), out);
			AddPrefixHtmlTitleHandler htmlTitleHandler = new AddPrefixHtmlTitleHandler(
					getMessage(request, "dashboard.show.htmlTitlePrefix", getMessage(request, "app.name")));
			HtmlRenderAttributes.setHtmlTitleHandler(renderContext, htmlTitleHandler);

			HtmlDashboard dashboard = dashboardWidget.render(renderContext);

			SessionHtmlDashboardManager dashboardManager = getSessionHtmlDashboardManagerNotNull(request);
			dashboardManager.put(dashboard);
		}
		finally
		{
			ChartWidgetSourceContext.remove();
		}
	}

	/**
	 * 看板数据。
	 * 
	 * @param request
	 * @param response
	 * @param model
	 * @param id
	 * @throws Exception
	 */
	@RequestMapping(value = "/showData", produces = CONTENT_TYPE_JSON)
	@ResponseBody
	public Map<String, DataSetResult[]> showData(HttpServletRequest request, HttpServletResponse response,
			org.springframework.ui.Model model) throws Exception
	{
		WebContext webContext = createWebContext(request);
		return getDashboardData(request, response, model, webContext);
	}

	/**
	 * 解析展示看板请求路径的看板资源名。
	 * <p>
	 * 返回空字符串表情请求展示首页。
	 * </p>
	 * 
	 * @param request
	 * @param response
	 * @param id
	 * @return
	 */
	protected String resolveDashboardResName(HttpServletRequest request, HttpServletResponse response,
			String id)
	{
		String pathInfo = request.getPathInfo();

		String idPath = id + "/";

		if (pathInfo.endsWith(id) || pathInfo.endsWith(idPath))
			return "";

		String resPath = pathInfo.substring(pathInfo.indexOf(idPath) + idPath.length());

		return resPath;
	}

	/**
	 * 解析HTML模板的字符编码。
	 * 
	 * @param templateIn
	 * @return
	 * @throws IOException
	 */
	protected String resolveTemplateEncoding(String templateContent) throws IOException
	{
		String templateEncoding = null;

		if (templateContent != null)
		{
			Reader in = null;
			try
			{
				in = IOUtil.getReader(templateContent);

				templateEncoding = this.htmlTplDashboardWidgetEntityService.getHtmlTplDashboardWidgetRenderer()
						.resolveCharset(in);
			}
			finally
			{
				IOUtil.close(in);
			}
		}

		if (StringUtil.isEmpty(templateEncoding))
			templateEncoding = HtmlTplDashboardWidget.DEFAULT_TEMPLATE_ENCODING;

		return templateEncoding;
	}

	/**
	 * 解析HTML模板文件的字符编码。
	 * 
	 * @param templateIn
	 * @return
	 * @throws IOException
	 */
	protected String resolveTemplateEncoding(File templateFile) throws IOException
	{
		String templateEncoding = null;

		InputStream in = null;
		try
		{
			in = IOUtil.getInputStream(templateFile);

			templateEncoding = this.htmlTplDashboardWidgetEntityService.getHtmlTplDashboardWidgetRenderer()
					.resolveCharset(in);
		}
		finally
		{
			IOUtil.close(in);
		}

		if (StringUtil.isEmpty(templateEncoding))
			templateEncoding = HtmlTplDashboardWidget.DEFAULT_TEMPLATE_ENCODING;

		return templateEncoding;
	}

	protected WebContext createWebContext(HttpServletRequest request)
	{
		String contextPath = getWebContextPath(request).get(request);
		return new WebContext(contextPath, contextPath + "/analysis/dashboard/showData");
	}

	protected void checkSaveEntity(HtmlTplDashboardWidgetEntity dashboard)
	{
		if (isBlank(dashboard.getName()))
			throw new IllegalInputException();

		if (isEmpty(dashboard.getTemplate()))
			throw new IllegalInputException();
	}

	protected String readAndSetTemplateContent(HtmlTplDashboardWidgetEntity dashboard,
			org.springframework.ui.Model model) throws IOException
	{
		String templateContent = this.htmlTplDashboardWidgetEntityService.getHtmlTplDashboardWidgetRenderer()
				.readTemplateContent(dashboard);

		model.addAttribute("templateContent", templateContent);

		return templateContent;
	}

	protected void saveTemplateContent(HtmlTplDashboardWidgetEntity dashboard, String templateContent)
			throws IOException
	{
		this.htmlTplDashboardWidgetEntityService.getHtmlTplDashboardWidgetRenderer().saveTemplateContent(dashboard,
				templateContent);
	}
}
