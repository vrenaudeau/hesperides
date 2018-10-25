/*
 *
 * This file is part of the Hesperides distribution.
 * (https://github.com/voyages-sncf-technologies/hesperides)
 * Copyright (c) 2016 VSCT.
 *
 * Hesperides is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, version 3.
 *
 * Hesperides is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */
package org.hesperides.core.domain.platforms.queries;

import org.axonframework.queryhandling.QueryGateway;
import org.hesperides.commons.axon.AxonQueries;
import org.hesperides.core.domain.platforms.*;
import org.hesperides.core.domain.platforms.entities.Platform;
import org.hesperides.core.domain.platforms.queries.views.*;
import org.hesperides.core.domain.platforms.queries.views.properties.AbstractValuedPropertyView;
import org.hesperides.core.domain.platforms.queries.views.properties.ValuedPropertyView;
import org.hesperides.core.domain.security.User;
import org.hesperides.core.domain.templatecontainers.entities.TemplateContainer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PlatformQueries extends AxonQueries {

    protected PlatformQueries(QueryGateway queryGateway) {
        super(queryGateway);
    }

    public Optional<String> getOptionalPlatformId(Platform.Key platformKey) {
        return querySyncOptional(new GetPlatformIdFromKeyQuery(platformKey), String.class);
    }

    public Optional<PlatformView> getOptionalPlatform(String platformId) {
        return querySyncOptional(new GetPlatformByIdQuery(platformId), PlatformView.class);
    }

    public Optional<PlatformView> getOptionalPlatform(Platform.Key platformKey) {
        return querySyncOptional(new GetPlatformByKeyQuery(platformKey), PlatformView.class);
    }

    public boolean platformExists(Platform.Key platformKey) {
        return querySync(new PlatformExistsByKeyQuery(platformKey), Boolean.class);
    }

    public Optional<ApplicationView> getApplication(String applicationName) {
        return querySyncOptional(new GetApplicationByNameQuery(applicationName), ApplicationView.class);
    }

    public List<InstancePropertyView> getInstanceModel(final Platform.Key platformKey, final String path, final User user) {
        return querySyncList(new GetInstanceModelQuery(platformKey, path, user), InstancePropertyView.class);
    }

    public List<ModulePlatformView> getPlatformsUsingModule(TemplateContainer.Key moduleKey) {
        return querySyncList(new GetPlatformsUsingModuleQuery(moduleKey), ModulePlatformView.class);
    }

    public List<SearchPlatformResultView> searchPlatforms(String applicationName, String platformName) {
        return querySyncList(new SearchPlatformsQuery(applicationName, platformName), SearchPlatformResultView.class);
    }

    public List<SearchApplicationResultView> searchApplications(String applicationName) {
        return querySyncList(new SearchApplicationsQuery(applicationName), SearchApplicationResultView.class);
    }

    public List<AbstractValuedPropertyView> getDeployedModuleProperties(final Platform.Key platformKey, final String path, final User user) {
        return querySyncList(new GetDeployedModulesPropertiesQuery(platformKey, path, user), AbstractValuedPropertyView.class);
    }

    public List<ValuedPropertyView> getGlobalProperties(final Platform.Key platformKey, final User user) {
        return querySyncList(new GetGlobalPropertiesQuery(platformKey, user), ValuedPropertyView.class);
    }
}
