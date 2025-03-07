/*
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
package io.prestosql.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public class CatalogAccessControlRule
{
    private final boolean allow;
    private final Optional<Pattern> userRegex;
    private final Optional<Pattern> groupRegex;
    private final Optional<Pattern> catalogRegex;

    @JsonCreator
    public CatalogAccessControlRule(
            @JsonProperty("allow") boolean allow,
            @JsonProperty("user") Optional<Pattern> userRegex,
            @JsonProperty("group") Optional<Pattern> groupRegex,
            @JsonProperty("catalog") Optional<Pattern> catalogRegex)
    {
        this.allow = allow;
        this.userRegex = requireNonNull(userRegex, "userRegex is null");
        this.groupRegex = requireNonNull(groupRegex, "group is null");
        this.catalogRegex = requireNonNull(catalogRegex, "catalogRegex is null");
    }

    public Optional<Boolean> match(String user, Set<String> groups, Optional<String> catalog)
    {
        if (userRegex.map(regex -> regex.matcher(user).matches()).orElse(true) &&
                groupRegex.map(regex -> groups.stream().anyMatch(group -> regex.matcher(group).matches())).orElse(true) &&
                catalogRegex.map(regex -> regex.matcher(catalog.orElse("")).matches()).orElse(true)) {
            return Optional.of(allow);
        }
        return Optional.empty();
    }
}
