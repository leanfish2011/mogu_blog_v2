package com.moxi.mogublog.web.restapi;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.moxi.mogublog.utils.IpUtils;
import com.moxi.mogublog.utils.ResultUtil;
import com.moxi.mogublog.utils.StringUtils;
import com.moxi.mogublog.web.feign.PictureFeignClient;
import com.moxi.mogublog.web.global.MessageConf;
import com.moxi.mogublog.web.global.RedisConf;
import com.moxi.mogublog.web.global.SQLConf;
import com.moxi.mogublog.web.global.SysConf;
import com.moxi.mogublog.web.log.BussinessLog;
import com.moxi.mogublog.web.util.WebUtils;
import com.moxi.mogublog.xo.entity.Blog;
import com.moxi.mogublog.xo.entity.Comment;
import com.moxi.mogublog.xo.entity.WebVisit;
import com.moxi.mogublog.xo.service.*;
import com.moxi.mougblog.base.enums.*;
import com.moxi.mougblog.base.global.ECode;
import com.moxi.mougblog.base.holder.RequestHolder;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 文章详情 RestApi
 * </p>
 *
 * @author xuzhixiang
 * @since 2018-09-04
 */
@RestController
@RequestMapping("/content")
@Api(value = "文章详情RestApi", tags = {"BlogContentRestApi"})
@Slf4j
public class BlogContentRestApi {

    @Autowired
    WebUtils webUtils;

    @Autowired
    TagService tagService;

    @Autowired
    BlogSortService blogSortService;

    @Autowired
    LinkService linkService;
    @Autowired
    CommentService commentService;
    @Autowired
    private BlogService blogService;
    @Autowired
    private PictureFeignClient pictureFeignClient;
    @Autowired
    private WebVisitService webVisitService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value(value = "${BLOG.ORIGINAL_TEMPLATE}")
    private String ORIGINAL_TEMPLATE;

    @Value(value = "${BLOG.REPRINTED_TEMPLATE}")
    private String REPRINTED_TEMPLATE;

    @BussinessLog(value = "点击博客", behavior = EBehavior.BLOG_CONTNET)
    @ApiOperation(value = "通过Uid获取博客内容", notes = "通过Uid获取博客内容")
    @GetMapping("/getBlogByUid")
    public String getBlogByUid(@ApiParam(name = "uid", value = "博客UID", required = false) @RequestParam(name = "uid", required = false) String uid) {

        HttpServletRequest request = RequestHolder.getRequest();
        String ip = IpUtils.getIpAddr(request);

        if (StringUtils.isEmpty(uid)) {
            return ResultUtil.result(SysConf.ERROR, "UID不能为空");
        }

        Blog blog = blogService.getById(uid);

        if (blog == null || blog.getStatus() == EStatus.DISABLED || blog.getIsPublish() == EPublish.NO_PUBLISH) {
            return ResultUtil.result(ECode.ERROR, "该文章已下架或被删除");
        }


        if (blog != null) {

            // 设置文章版权申明
            setBlogCopyright(blog);

            //设置博客标签
            blogService.setTagByBlog(blog);

            //获取分类
            blogService.setSortByBlog(blog);

            //设置博客标题图
            setPhotoListByBlog(blog);

            //从Redis取出数据，判断该用户是否点击过
            String jsonResult = stringRedisTemplate.opsForValue().get("BLOG_CLICK:" + ip + "#" + uid);

            if (StringUtils.isEmpty(jsonResult)) {

                //给博客点击数增加
                Integer clickCount = blog.getClickCount() + 1;
                blog.setClickCount(clickCount);
                blog.updateById();

                //将该用户点击记录存储到redis中, 24小时后过期
                stringRedisTemplate.opsForValue().set("BLOG_CLICK:" + ip + "#" + uid, blog.getClickCount().toString(),
                        24, TimeUnit.HOURS);
            }
        }

        log.info("返回结果");
        return ResultUtil.result(SysConf.SUCCESS, blog);
    }

    @ApiOperation(value = "通过Uid获取博客点赞数", notes = "通过Uid获取博客点赞数")
    @GetMapping("/getBlogPraiseCountByUid")
    public String getBlogPraiseCountByUid(@ApiParam(name = "uid", value = "博客UID", required = false) @RequestParam(name = "uid", required = false) String uid) {

        HttpServletRequest request = RequestHolder.getRequest();
        String ip = IpUtils.getIpAddr(request);

        if (StringUtils.isEmpty(uid)) {
            return ResultUtil.result(SysConf.ERROR, MessageConf.PARAM_INCORRECT);
        }

        //从Redis取出用户点赞数据
        String pariseJsonResult = stringRedisTemplate.opsForValue().get(RedisConf.BLOG_PRAISE + RedisConf.SEGMENTATION + uid);
        Integer pariseCount = 0;
        if (!StringUtils.isEmpty(pariseJsonResult)) {
            pariseCount = Integer.parseInt(pariseJsonResult);
        }
        return ResultUtil.result(SysConf.SUCCESS, pariseCount);
    }

    @BussinessLog(value = "通过Uid给博客点赞", behavior = EBehavior.BLOG_PRAISE)
    @ApiOperation(value = "通过Uid给博客点赞", notes = "通过Uid给博客点赞")
    @GetMapping("/praiseBlogByUid")
    public String praiseBlogByUid(@ApiParam(name = "uid", value = "博客UID", required = false) @RequestParam(name = "uid", required = false) String uid) {

        if (StringUtils.isEmpty(uid)) {
            return ResultUtil.result(SysConf.ERROR, MessageConf.PARAM_INCORRECT);
        }

        HttpServletRequest request = RequestHolder.getRequest();
        String ip = IpUtils.getIpAddr(request);

        //判断该IP是否点赞过
        if (request.getAttribute(SysConf.USER_UID) != null) {
            // 如果用户登录了
            String userUid = request.getAttribute(SysConf.USER_UID).toString();
            QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq(SQLConf.USER_UID, userUid);
            queryWrapper.eq(SQLConf.BLOG_UID, uid);
            queryWrapper.eq(SQLConf.TYPE, ECommentType.PRAISE);
            queryWrapper.last("LIMIT 1");
            Comment praise = commentService.getOne(queryWrapper);
            if (praise != null) {
                return ResultUtil.result(SysConf.ERROR, "您已经点过赞了！");
            }
        } else {
            // 如果用户未登录
            QueryWrapper<WebVisit> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq(SQLConf.IP, ip);
            queryWrapper.eq(SQLConf.MODULE_UID, uid);
            queryWrapper.eq(SQLConf.BEHAVIOR, EBehavior.BLOG_PRAISE);
            queryWrapper.last("LIMIT 1");
            WebVisit webVisit = webVisitService.getOne(queryWrapper);
            if (webVisit != null) {
                return ResultUtil.result(SysConf.ERROR, "您已经点过赞了！");
            }
        }

        Blog blog = blogService.getById(uid);

        String pariseJsonResult = stringRedisTemplate.opsForValue().get(RedisConf.BLOG_PRAISE + RedisConf.SEGMENTATION + uid);

        if (StringUtils.isEmpty(pariseJsonResult)) {

            //给该博客点赞数
            stringRedisTemplate.opsForValue().set(RedisConf.BLOG_PRAISE + RedisConf.SEGMENTATION + uid, "1");

            blog.setCollectCount(1);
            blog.updateById();

        } else {

            Integer count = blog.getCollectCount() + 1;

            //给该博客点赞 +1
            stringRedisTemplate.opsForValue().set(RedisConf.BLOG_PRAISE + RedisConf.SEGMENTATION + uid, count.toString());

            blog.setCollectCount(count);
            blog.updateById();
        }

        // 已登录用户，向评论表添加点赞数据
        if (request.getAttribute(SysConf.USER_UID) != null) {
            String userUid = request.getAttribute(SysConf.USER_UID).toString();
            Comment comment = new Comment();
            comment.setUserUid(userUid);
            comment.setBlogUid(uid);
            comment.setSource(ECommentSource.BLOG_INFO.getCode());
            comment.setType(ECommentType.PRAISE);
            comment.insert();
        }

        return ResultUtil.result(SysConf.SUCCESS, blog.getCollectCount());
    }

    @ApiOperation(value = "根据标签Uid获取相关的博客", notes = "根据标签获取相关的博客")
    @GetMapping("/getSameBlogByTagUid")
    public String getSameBlogByTagUid(HttpServletRequest request,
                                      @ApiParam(name = "tagUid", value = "博客标签UID", required = true) @RequestParam(name = "tagUid", required = true) String tagUid,
                                      @ApiParam(name = "currentPage", value = "当前页数", required = false) @RequestParam(name = "currentPage", required = false, defaultValue = "1") Long currentPage,
                                      @ApiParam(name = "pageSize", value = "每页显示数目", required = false) @RequestParam(name = "pageSize", required = false, defaultValue = "10") Long pageSize) {
        if (StringUtils.isEmpty(tagUid)) {
            return ResultUtil.result(SysConf.ERROR, "标签UID不能为空");
        }

        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        Page<Blog> page = new Page<>();
        page.setCurrent(currentPage);
        page.setSize(pageSize);
        queryWrapper.like(SQLConf.TagUid, tagUid);
        queryWrapper.orderByDesc(SQLConf.CREATE_TIME);
        queryWrapper.eq(SQLConf.STATUS, EStatus.ENABLE);
        queryWrapper.eq(SQLConf.IS_PUBLISH, EPublish.PUBLISH);
        IPage<Blog> pageList = blogService.page(page, queryWrapper);
        List<Blog> list = pageList.getRecords();

        list = blogService.setTagAndSortByBlogList(list);
        log.info("返回结果");
        pageList.setRecords(list);
        return ResultUtil.result(SysConf.SUCCESS, pageList);
    }

    @ApiOperation(value = "根据BlogUid获取相关的博客", notes = "根据BlogUid获取相关的博客")
    @GetMapping("/getSameBlogByBlogUid")
    public String getSameBlogByBlogUid(HttpServletRequest request,
                                       @ApiParam(name = "blogUid", value = "博客标签UID", required = true) @RequestParam(name = "blogUid", required = true) String blogUid,
                                       @ApiParam(name = "currentPage", value = "当前页数", required = false) @RequestParam(name = "currentPage", required = false, defaultValue = "1") Long currentPage,
                                       @ApiParam(name = "pageSize", value = "每页显示数目", required = false) @RequestParam(name = "pageSize", required = false, defaultValue = "10") Long pageSize) {
        if (StringUtils.isEmpty(blogUid)) {
            return ResultUtil.result(SysConf.ERROR, "博客UID不能为空");
        }

        Blog blog = blogService.getById(blogUid);

        if (blog == null || blog.getStatus() == EStatus.DISABLED) {
            return ResultUtil.result(SysConf.ERROR, "该博客不存在");
        }

        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SQLConf.STATUS, EStatus.ENABLE);
        Page<Blog> page = new Page<>();
        page.setCurrent(currentPage);
        page.setSize(pageSize);
        // 通过分类来获取相关博客
        String blogSortUid = blog.getBlogSortUid();
        queryWrapper.eq(SQLConf.BLOG_SORT_UID, blogSortUid);
        queryWrapper.eq(SQLConf.IS_PUBLISH, EPublish.PUBLISH);
        queryWrapper.orderByDesc(SQLConf.CREATE_TIME);
        IPage<Blog> pageList = blogService.page(page, queryWrapper);
        List<Blog> list = pageList.getRecords();

        list = blogService.setTagAndSortByBlogList(list);

        //过滤掉当前的博客
        List<Blog> newList = new ArrayList<>();
        for (Blog item : list) {
            if (item.getUid().equals(blogUid)) {
                continue;
            }
            newList.add(item);
        }

        log.info("返回结果");
        pageList.setRecords(newList);
        return ResultUtil.result(SysConf.SUCCESS, pageList);
    }

    /**
     * 设置博客标题图
     *
     * @param blog
     */
    private void setPhotoListByBlog(Blog blog) {
        //获取标题图片
        if (blog != null && !StringUtils.isEmpty(blog.getFileUid())) {
            String result = this.pictureFeignClient.getPicture(blog.getFileUid(), ",");
            List<String> picList = webUtils.getPicture(result);
            log.info("##### picList: #######" + picList);
            if (picList != null && picList.size() > 0) {
                blog.setPhotoList(picList);
            }
        }
    }

    /**
     * 设置博客版权
     *
     * @param blog
     */
    private void setBlogCopyright(Blog blog) {

        //如果是原创的话
        if (blog.getIsOriginal().equals("1")) {
            blog.setCopyright(ORIGINAL_TEMPLATE);
        } else {
            String reprintedTemplate = REPRINTED_TEMPLATE;
            String[] variable = {blog.getArticlesPart(), blog.getAuthor()};
            String str = String.format(reprintedTemplate, variable);
            blog.setCopyright(str);
        }
    }
}

