package com.jd.journalq.sync;

import com.alibaba.fastjson.JSON;
import com.jd.journalq.exception.ServiceException;
import com.jd.journalq.model.domain.Application;
import com.jd.journalq.model.domain.ApplicationUser;
import com.jd.journalq.model.domain.Identity;
import com.jd.journalq.model.domain.User;
import com.jd.journalq.model.domain.User.UserStatus;
import com.jd.journalq.service.ApplicationService;
import com.jd.journalq.service.UserService;
import com.jd.journalq.util.NullUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.jd.journalq.exception.ServiceException.INTERNAL_SERVER_ERROR;

@Service("syncService")
public class SyncServiceImpl implements SyncService {
    private static final Logger logger = LoggerFactory.getLogger(SyncServiceImpl.class);
    @Autowired
    @Qualifier("applicationSupplier")
    protected ApplicationSupplier applicationSupplier;

    @Autowired
    protected UserSupplier userSupplier;

    @Autowired
    protected ApplicationService applicationService;

    @Autowired
    protected UserService userService;

    public void setUserSupplier(UserSupplier userSupplier) {
        this.userSupplier = userSupplier;
    }

    public void setApplicationService(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ApplicationInfo syncApp(final Application application) throws Exception {
        if (application == null) {
            return null;
        }
        if (applicationSupplier == null) {
            return null;
        }
        ApplicationInfo info = null;
        if (application.getSource() == 0) {
            info = new ApplicationInfo();
            //这个值为空
            application.setAliasCode(null);
            info.setCode(application.getCode());
            info.setName(application.getCode());
            info.setAliasCode(application.getCode());
            info.setSystem(application.getCode());
            info.setSource(Application.OTHER_SOURCE);
            if (!NullUtil.isEmpty(application.getOwner())&&StringUtils.isNotEmpty(application.getOwner().getCode())) {
                info.setOwner(new UserInfo(application.getOwner().getCode()));
            } else {
                info.setOwner(new UserInfo(application.getErp()));
            }
            UserInfo userInfo = new UserInfo();
            userInfo.setCode(application.getErp());
            info.setMembers(Arrays.asList(userInfo));
        } else {
            info = applicationSupplier.findByCode(application.getAliasCode(), application.getSource());
            logger.info("sync info:{}", JSON.toJSONString(info));
        }
        if (info == null) {
            return null;
        }
        if (application.getAliasCode() != null) {
            info.setAliasCode(application.getAliasCode());
        }
        if (StringUtils.isNotEmpty(application.getCode())) {
            info.setCode(application.getCode());
        }
        if (!loadUser(info.getOwner())) {
            info.setOwner(null);
        }
        List<UserInfo> members = info.getMembers();
        if (members != null) {
            for (UserInfo userInfo:members){
                if (!loadUser(userInfo)) {
                    //不存在
                    members.remove(userInfo);
                }
            }
            info.setMembers(members);
        }

        return info;
    }

    @Override
    public UserInfo syncUser(final User user) throws Exception {
        if (user == null) {
            return null;
        }
        UserInfo result = userSupplier.findByCode(user.getCode());
        if (result == null) {
            //用户不存在了
            result = new UserInfo();
            result.apply(user);
            result.setStatus(UserStatus.UNABLE.value());
        } else {
            result.setId(user.getId());
            result.setStatus(user.getStatus());
            result.setRole(user.getRole());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.REQUIRED)
    public Application addOrUpdateApp(final ApplicationInfo info) {
        if (info == null) {
            return null;
        }
        int sign = info.hashCode();
        //判断应用是否发生变更
        Application application = applicationService.findByCode(info.getCode());
        if (application != null && application.getSign() == sign && application.getStatus() != -1) {
            //没有变化
            throw new ServiceException(INTERNAL_SERVER_ERROR, "application already sync.");
        }
        Application target = application == null ? new Application() : application;
        target.setCode(info.getCode());
        target.setName(info.getName());
        target.setAliasCode(info.getAliasCode());
        target.setSource(info.getSource());
        target.setSystem(info.getSystem());
        target.setSign(sign);
        //保存负责人和成员
        UserInfo owner = info.getOwner();
        if (owner != null) {
            //设置修改人
            owner.setUser(info.getUser());
            save(owner);
            //获取负责人ID
            target.setOwner(new Identity(owner));
            //设置应用所属部门
            target.setDepartment(owner.getOrgName());
        }
        if (info.getMembers() != null) {
            for (UserInfo member : info.getMembers()) {
                //设置修改人
                member.setUser(info.getUser());
                save(member);
            }
        }
        if (application == null) {
            target.setCreateBy(info.getUser());
            target.setUpdateBy(info.getUser());
            //新应用
            applicationService.add(target);
            addAppUser(info, target);
            application = applicationService.findByCode(info.getCode());
        } else {
            //更新应用
            target.setUpdateBy(info.getUser());
            applicationService.update(target);
            updateAppUser(info, target);
        }
        return application;

    }

    /**
     * 加载用户
     *
     * @param userInfo
     * @return
     */
    protected boolean loadUser(final UserInfo userInfo) throws Exception {
        if (userInfo == null) {
            return false;
        }
        User user = userService.findByCode(userInfo.getCode());
        if (user != null) {
            userInfo.apply(user);
            return true;
        } else {
            UserInfo erp = userSupplier.findByCode(userInfo.getCode());
            if (erp != null && erp.getCode() != null) {
                userInfo.apply(erp);
                userInfo.setId(0);
                return true;
            }
            return false;
        }
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.REQUIRED)
    public User addOrUpdateUser(final UserInfo info) {
        if (info == null) {
            return null;
        }
        User user = userService.findByCode(info.getCode());
        if (user != null) {
            //先使用默认状态计算签名
            int sign = info.hashCode();
            if (user.getSign() == sign) {
                //没有发生变化
                return user;
            }
            //保持ID，角色和状态不发生变化
            info.setId(user.getId());
            info.setRole(user.getRole());
            info.setStatus(user.getStatus());
            info.toUser(user, sign);
            userService.update(user);
        } else {
            user = info.toUser();
            userService.add(user);
        }
        return user;
    }

    /**
     * 保存用户
     *
     * @param info
     * @return
     */
    protected int save(final UserInfo info) {
        if (info == null) {
            return 0;
        }
        int sign = info.hashCode();
        if (info.getId() > 0 && info.getSign() == sign) {
            //没有发生变化
            return 0;
        }
        int updCount = 0;
        User user = info.toUser(sign);
        if (user.getId() <= 0) {
            updCount = userService.add(user);
        } else {
            updCount = userService.update(user);
        }
        info.setId(user.getId());
        return updCount;
    }

    /**
     * 更新应用用户
     *
     * @param info
     * @param application
     * @return
     */
    protected int updateAppUser(final ApplicationInfo info, final Application application) {
        int result = 0;
        //当前的用户信息
        Map<String, UserInfo> members = new HashMap<>();
        //判断负责人
        if (info.getOwner() != null) {
            members.put(info.getOwner().getCode(), info.getOwner());
        }
        //检查用户
        if (info.getMembers() != null) {
            for (UserInfo member : info.getMembers()) {
                if (member != null) {
                    members.put(member.getCode(), member);
                }
            }
        }
        //数据库的用户信息
        List<User> users = userService.findByAppId(application.getId());
        if (users != null) {
            UserInfo member;
            for (User user : users) {
                member = members.remove(user.getCode());
                if (member == null) {
                    userService.deleteAppUser(user.getId(), info.getId());
                }
            }
        }
        if (!members.isEmpty()) {
            for (Map.Entry<String, UserInfo> entry : members.entrySet()) {
                addAppUser(entry.getValue(), application);
            }
        }
        return result;
    }

    /**
     * 增加新应用的用户
     *
     * @param info
     * @param application
     * @return
     */
    protected int addAppUser(final ApplicationInfo info, final Application application) {
        int result = 0;
        //判断负责人
        addAppUser(info.getOwner(), application);
        //检查用户
        if (info.getMembers() != null) {
            for (UserInfo member : info.getMembers()) {
                if (member != null) {
                    addAppUser(member, application);
                }
            }
        }
        return result;
    }

    /**
     * 添加应用用户
     *
     * @param info
     * @param application
     * @return
     */
    protected int addAppUser(final UserInfo info, final Application application) {
        if (info == null) {
            return 0;
        }
        try {
            ApplicationUser applicationUser = new ApplicationUser(application.identity(), info.identity());
            applicationUser.setCreateBy(info.getUser());
            applicationUser.setUpdateBy(info.getUser());
            return userService.addAppUser(applicationUser);
        } catch (DuplicateKeyException e) {
            //忽略唯一索引冲突
            return 0;
        }
    }


}