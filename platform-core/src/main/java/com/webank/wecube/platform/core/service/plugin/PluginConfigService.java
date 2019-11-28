package com.webank.wecube.platform.core.service.plugin;


import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.domain.plugin.*;
import com.webank.wecube.platform.core.dto.PluginConfigDto;
import com.webank.wecube.platform.core.dto.PluginConfigInterfaceDto;
import com.webank.wecube.platform.core.jpa.PluginConfigInterfaceRepository;
import com.webank.wecube.platform.core.jpa.PluginConfigRepository;
import com.webank.wecube.platform.core.jpa.PluginPackageEntityRepository;
import com.webank.wecube.platform.core.jpa.PluginPackageRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.Lists.newArrayList;
import static com.webank.wecube.platform.core.domain.plugin.PluginConfig.Status.*;
import static com.webank.wecube.platform.core.domain.plugin.PluginPackage.Status.DECOMMISSIONED;
import static com.webank.wecube.platform.core.domain.plugin.PluginPackage.Status.UNREGISTERED;
import static com.webank.wecube.platform.core.dto.PluginConfigInterfaceParameterDto.MappingType.entity;
import static com.webank.wecube.platform.core.dto.PluginConfigInterfaceParameterDto.MappingType.system_variable;

@Service
@Transactional
public class PluginConfigService {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private PluginPackageRepository pluginPackageRepository;
    @Autowired
    private PluginConfigRepository pluginConfigRepository;
    @Autowired
    private PluginConfigInterfaceRepository pluginConfigInterfaceRepository;
    @Autowired
    private PluginPackageEntityRepository pluginPackageEntityRepository;

    public List<PluginConfigInterface> getPluginConfigInterfaces(int pluginConfigId) {
        return pluginConfigRepository.findAllPluginConfigInterfacesByConfigIdAndFetchParameters(pluginConfigId);
    }

    public PluginConfigDto savePluginConfig(PluginConfigDto pluginConfigDto) throws WecubeCoreException {
        ensurePluginConfigIsValid(pluginConfigDto);
        Integer packageId = pluginConfigDto.getPluginPackageId();
        PluginPackage pluginPackage = pluginPackageRepository.findById(packageId).get();

        PluginConfig pluginConfig = pluginConfigDto.toDomain(pluginPackage);
        PluginConfig pluginConfigFromDatabase = pluginConfigRepository.findById(pluginConfigDto.getId()).get();
        if (ENABLED == pluginConfigFromDatabase.getStatus()) {
            throw new WecubeCoreException("Not allow to update plugin with status: ENABLED");
        }
        pluginConfig.setStatus(DISABLED);

        PluginConfig savedPluginConfig = pluginConfigRepository.save(pluginConfig);
        return PluginConfigDto.fromDomain(savedPluginConfig);
    }

    private void ensurePluginConfigIsValid(PluginConfigDto pluginConfig) {
        if (null == pluginConfig.getPluginPackageId()
                || pluginConfig.getPluginPackageId() < 1
                || !pluginPackageRepository.existsById(pluginConfig.getPluginPackageId())) {
            throw new WecubeCoreException(String.format("Cannot find PluginPackage with id=%s in PluginConfig", pluginConfig.getPluginPackageId()));
        }
        if (null == pluginConfig.getId() || pluginConfig.getId() < 1) {
            throw new WecubeCoreException("Invalid pluginConfig with id: " + pluginConfig.getId());
        }
        if (!pluginConfigRepository.existsById(pluginConfig.getId())) {
            throw new WecubeCoreException("PluginConfig not found for id: " + pluginConfig.getId());
        }

        Integer entityId = pluginConfig.getEntityId();
        if (null != entityId && entityId.intValue() > 0) {
            Optional<PluginPackageEntity> pluginPackageEntityOptional = pluginPackageEntityRepository.findById(entityId);
            if (!pluginPackageEntityOptional.isPresent()) {
                String errorMessage = String.format("PluginPackageEntity not found for id: [%s] for plugin config: %s", entityId, pluginConfig.getName());
                log.error(errorMessage);
                throw new WecubeCoreException(errorMessage);
            }
        }
    }

    public PluginConfigDto enablePlugin(int pluginConfigId) {
        if (!pluginConfigRepository.existsById(pluginConfigId)) {
            throw new WecubeCoreException("PluginConfig not found for id: " + pluginConfigId);
        }

        PluginConfig pluginConfig = pluginConfigRepository.findById(pluginConfigId).get();

        if (pluginConfig.getPluginPackage() == null || UNREGISTERED == pluginConfig.getPluginPackage().getStatus() || DECOMMISSIONED == pluginConfig.getPluginPackage().getStatus()) {
            throw new WecubeCoreException("Plugin package is not in valid status [REGISTERED, RUNNING, STOPPED] to enable plugin.");
        }

        if (DISABLED != pluginConfig.getStatus()) {
            throw new WecubeCoreException("Not allow to enable pluginConfig with status: ENABLED");
        }

        Integer entityId = pluginConfig.getEntityId();
        if (null != entityId && entityId.intValue() > 0) {
            Optional<PluginPackageEntity> pluginPackageEntityOptional = pluginPackageEntityRepository.findById(entityId);
            if (!pluginPackageEntityOptional.isPresent()) {
                String errorMessage = String.format("PluginPackageEntity not found for id: [%s] for plugin config: %s", entityId, pluginConfig.getName());
                log.error(errorMessage);
                throw new WecubeCoreException(errorMessage);
            }
        }

        checkMandatoryParameters(pluginConfig);

        pluginConfig.setStatus(ENABLED);
        return PluginConfigDto.fromDomain(pluginConfigRepository.save(pluginConfig));
    }

    private void checkMandatoryParameters(PluginConfig pluginConfig) {
        Set<PluginConfigInterface> interfaces = pluginConfig.getInterfaces();
        if (null != interfaces && interfaces.size() > 0) {
            interfaces.forEach(intf->{
                Set<PluginConfigInterfaceParameter> inputParameters = intf.getInputParameters();
                if (null != inputParameters && inputParameters.size() > 0){
                    inputParameters.forEach(inputParameter -> {
                        if ("Y".equalsIgnoreCase(inputParameter.getRequired())) {
                            if (system_variable.name().equals(inputParameter.getMappingType()) && inputParameter.getMappingSystemVariableId() == null ) {
                                throw new WecubeCoreException(String.format("System variable is required for parameter [%s]", inputParameter.getId()));
                            }
                            if (entity.name().equals(inputParameter.getMappingType()) && StringUtils.isBlank(inputParameter.getMappingEntityExpression())) {
                                throw new WecubeCoreException(String.format("Entity expression is required for parameter [%s]", inputParameter.getId()));
                            }
                        }
                    });
                }
                Set<PluginConfigInterfaceParameter> outputParameters = intf.getOutputParameters();
                if (null != outputParameters && outputParameters.size() > 0) {
                    outputParameters.forEach(outputParameter -> {
                        if ("Y".equalsIgnoreCase(outputParameter.getRequired())) {
                            if (entity.name().equals(outputParameter.getMappingType()) && StringUtils.isBlank(outputParameter.getMappingEntityExpression())) {
                                throw new WecubeCoreException(String.format("Entity expression is required for parameter [%s]", outputParameter.getId()));
                            }
                        }
                    });
                }
                    }
            );
        }
    }

    public PluginConfigDto disablePlugin(int pluginConfigId) {
        if (!pluginConfigRepository.existsById(pluginConfigId)) {
            throw new WecubeCoreException("PluginConfig not found for id: " + pluginConfigId);
        }

        PluginConfig pluginConfig = pluginConfigRepository.findById(pluginConfigId).get();

        pluginConfig.setStatus(DISABLED);
        return PluginConfigDto.fromDomain(pluginConfigRepository.save(pluginConfig));
    }
    
    public PluginConfigInterface getPluginConfigInterfaceByServiceName(String serviceName) {
        Optional<PluginConfigInterface> pluginConfigInterface = pluginConfigRepository
                .findLatestOnlinePluginConfigInterfaceByServiceNameAndFetchParameters(serviceName);
        if (!pluginConfigInterface.isPresent()) {
            throw new WecubeCoreException(
                    String.format("Plugin interface not found for serviceName [%s].", serviceName));
        }
        return pluginConfigInterface.get();
    }

    public List<PluginConfigInterfaceDto> queryAllEnabledPluginConfigInterface() {
        Optional<List<PluginConfigInterface>> pluginConfigsOptional = pluginConfigInterfaceRepository.findPluginConfigInterfaceByPluginConfig_Status(ENABLED);
        List<PluginConfigInterfaceDto> pluginConfigInterfaceDtos = newArrayList();
        if (pluginConfigsOptional.isPresent()) {
            List<PluginConfigInterface> pluginConfigInterfaces = pluginConfigsOptional.get();
            pluginConfigInterfaces.forEach(pluginConfigInterface -> pluginConfigInterfaceDtos.add(PluginConfigInterfaceDto.fromDomain(pluginConfigInterface)));
        }
        return pluginConfigInterfaceDtos;
    }

    public List<PluginConfigInterfaceDto> queryAllEnabledPluginConfigInterfaceForEntity(int entityId) {
        Optional<List<PluginConfigInterface>> pluginConfigsOptional = pluginConfigInterfaceRepository.findPluginConfigInterfaceByPluginConfig_EntityIdAndPluginConfig_Status(entityId, ENABLED);
        List<PluginConfigInterfaceDto> pluginConfigInterfaceDtos = newArrayList();
        if (pluginConfigsOptional.isPresent()) {
            List<PluginConfigInterface> pluginConfigInterfaces = pluginConfigsOptional.get();
            pluginConfigInterfaces.forEach(pluginConfigInterface -> pluginConfigInterfaceDtos.add(PluginConfigInterfaceDto.fromDomain(pluginConfigInterface)));
        }
        return pluginConfigInterfaceDtos;
    }
}
