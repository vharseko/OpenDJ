/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2008 Sun Microsystems, Inc.
 *      Portions Copyright 2014 ForgeRock AS
 */
package org.opends.server.core;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.util.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.opends.server.admin.ClassPropertyDefinition;
import org.opends.server.admin.server.ConfigurationAddListener;
import org.opends.server.admin.server.ConfigurationChangeListener;
import org.opends.server.admin.server.ConfigurationDeleteListener;
import org.opends.server.admin.std.server.MatchingRuleCfg;
import org.opends.server.admin.std.server.RootCfg;
import org.opends.server.admin.server.ServerManagementContext;
import org.opends.server.admin.std.meta.MatchingRuleCfgDefn;
import org.opends.server.api.MatchingRule;
import org.opends.server.api.MatchingRuleFactory;
import org.opends.server.api.SubstringMatchingRule;
import org.forgerock.opendj.config.server.ConfigException;
import org.opends.server.types.AttributeType;
import org.opends.server.types.ConfigChangeResult;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.DN;
import org.opends.server.types.InitializationException;
import org.opends.server.types.MatchingRuleUse;
import org.forgerock.opendj.ldap.ResultCode;

import static org.opends.messages.ConfigMessages.*;
import static org.opends.server.util.StaticUtils.*;

/**
 * This class defines a utility that will be used to manage the set of matching
 * rules defined in the Directory Server.  It wil initialize the rules when the
 * server starts, and then will manage any additions, removals, or modifications
 * to any matching rules while the server is running.
 */
public class MatchingRuleConfigManager
       implements ConfigurationChangeListener<MatchingRuleCfg>,
                  ConfigurationAddListener<MatchingRuleCfg>,
                  ConfigurationDeleteListener<MatchingRuleCfg>

{

  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  // A mapping between the DNs of the config entries and the associated matching
  // rule Factories.
  private ConcurrentHashMap<DN,MatchingRuleFactory> matchingRuleFactories;



  /**
   * Creates a new instance of this matching rule config manager.
   */
  public MatchingRuleConfigManager()
  {
    matchingRuleFactories = new ConcurrentHashMap<DN,MatchingRuleFactory>();
  }



  /**
   * Initializes all matching rules after reading all the Matching Rule
   * factories currently defined in the Directory Server configuration.
   * This should only be called at Directory Server startup.
   *
   * @throws  ConfigException  If a configuration problem causes the matching
   *                           rule initialization process to fail.
   *
   * @throws  InitializationException  If a problem occurs while initializing
   *                                   the matching rules that is not related to
   *                                   the server configuration.
   */
  public void initializeMatchingRules()
         throws ConfigException, InitializationException
  {
    // Get the root configuration object.
    ServerManagementContext managementContext =
         ServerManagementContext.getInstance();
    RootCfg rootConfiguration =
         managementContext.getRootConfiguration();


    // Register as an add and delete listener with the root configuration so we
    // can be notified if any matching rule entries are added or removed.
    rootConfiguration.addMatchingRuleAddListener(this);
    rootConfiguration.addMatchingRuleDeleteListener(this);


    //Initialize the existing matching rules.
    for (String name : rootConfiguration.listMatchingRules())
    {
      MatchingRuleCfg mrConfiguration = rootConfiguration.getMatchingRule(name);
      mrConfiguration.addChangeListener(this);

      if (mrConfiguration.isEnabled())
      {
        String className = mrConfiguration.getJavaClass();
        try
        {
          MatchingRuleFactory<?> factory =
               loadMatchingRuleFactory(className, mrConfiguration, true);

          try
          {
            for(MatchingRule matchingRule: factory.getMatchingRules())
            {
              DirectoryServer.registerMatchingRule(matchingRule, false);
            }
            matchingRuleFactories.put(mrConfiguration.dn(), factory);
          }
          catch (DirectoryException de)
          {
            logger.warn(WARN_CONFIG_SCHEMA_MR_CONFLICTING_MR, mrConfiguration.dn(), de.getMessageObject());
            continue;
          }
        }
        catch (InitializationException ie)
        {
          logger.error(ie.getMessageObject());
          continue;
        }
      }
    }
  }



  /**
   * {@inheritDoc}
   */
  public boolean isConfigurationAddAcceptable(MatchingRuleCfg configuration,
                      List<LocalizableMessage> unacceptableReasons)
  {
    if (configuration.isEnabled())
    {
      // Get the name of the class and make sure we can instantiate it as a
      // matching rule Factory.
      String className = configuration.getJavaClass();
      try
      {
        loadMatchingRuleFactory(className, configuration, false);
      }
      catch (InitializationException ie)
      {
        unacceptableReasons.add(ie.getMessageObject());
        return false;
      }
    }

    // If we've gotten here, then it's fine.
    return true;
  }



  /**
   * {@inheritDoc}
   */
  public ConfigChangeResult applyConfigurationAdd(MatchingRuleCfg configuration)
  {
    ResultCode        resultCode          = ResultCode.SUCCESS;
    boolean           adminActionRequired = false;
    ArrayList<LocalizableMessage> messages            = new ArrayList<LocalizableMessage>();

    configuration.addChangeListener(this);

    if (! configuration.isEnabled())
    {
      return new ConfigChangeResult(resultCode, adminActionRequired, messages);
    }

    MatchingRuleFactory<?> factory = null;

    // Get the name of the class and make sure we can instantiate it as a
    // matching rule Factory.
    String className = configuration.getJavaClass();
    try
    {
      factory = loadMatchingRuleFactory(className, configuration, true);

      try
      {
        for(MatchingRule matchingRule: factory.getMatchingRules())
        {
          DirectoryServer.registerMatchingRule(matchingRule, false);
        }
        matchingRuleFactories.put(configuration.dn(),factory);
      }
      catch (DirectoryException de)
      {
        messages.add(WARN_CONFIG_SCHEMA_MR_CONFLICTING_MR.get(
            configuration.dn(), de.getMessageObject()));

        if (resultCode == ResultCode.SUCCESS)
        {
          resultCode = DirectoryServer.getServerErrorResultCode();
        }
      }
    }
    catch (InitializationException ie)
    {
      if (resultCode == ResultCode.SUCCESS)
      {
        resultCode = DirectoryServer.getServerErrorResultCode();
      }

      messages.add(ie.getMessageObject());
    }

    return new ConfigChangeResult(resultCode, adminActionRequired, messages);
  }



  /**
   * {@inheritDoc}
   */
  public boolean isConfigurationDeleteAcceptable(MatchingRuleCfg configuration,
                      List<LocalizableMessage> unacceptableReasons)
  {
    // If the matching rule is enabled, then check to see if there are any
    // defined attribute types or matching rule uses that use the matching rule.
    // If so, then don't allow it to be deleted.
    boolean configAcceptable = true;
    MatchingRuleFactory<?> factory =
            matchingRuleFactories.get(configuration.dn());
    for(MatchingRule matchingRule: factory.getMatchingRules())
    {
      if (matchingRule != null)
      {
        String oid = matchingRule.getOID();
        for (AttributeType at : DirectoryServer.getAttributeTypes().values())
        {
          MatchingRule amr = at.getApproximateMatchingRule();
          if ((amr != null) && oid.equals(amr.getOID()))
          {
            LocalizableMessage message =
                    WARN_CONFIG_SCHEMA_CANNOT_DELETE_MR_IN_USE_BY_AT.get(
                            matchingRule.getNameOrOID(),
                            at.getNameOrOID());
            unacceptableReasons.add(message);

            configAcceptable = false;
            continue;
          }

          MatchingRule emr = at.getEqualityMatchingRule();
          if ((emr != null) && oid.equals(emr.getOID()))
          {
            LocalizableMessage message =
                    WARN_CONFIG_SCHEMA_CANNOT_DELETE_MR_IN_USE_BY_AT.get(
                            matchingRule.getNameOrOID(), at.getNameOrOID());
            unacceptableReasons.add(message);

            configAcceptable = false;
            continue;
          }

          MatchingRule omr = at.getOrderingMatchingRule();
          if ((omr != null) && oid.equals(omr.getOID()))
          {
            LocalizableMessage message =
                    WARN_CONFIG_SCHEMA_CANNOT_DELETE_MR_IN_USE_BY_AT.get(
                            matchingRule.getNameOrOID(), at.getNameOrOID());
            unacceptableReasons.add(message);

            configAcceptable = false;
            continue;
          }

          SubstringMatchingRule smr = at.getSubstringMatchingRule();
          if ((smr != null) && oid.equals(smr.getOID()))
          {
            LocalizableMessage message =
                    WARN_CONFIG_SCHEMA_CANNOT_DELETE_MR_IN_USE_BY_AT.get(
                            matchingRule.getNameOrOID(), at.getNameOrOID());
            unacceptableReasons.add(message);

            configAcceptable = false;
            continue;
          }
        }

        for (MatchingRuleUse mru :
                DirectoryServer.getMatchingRuleUses().values())
        {
          if (oid.equals(mru.getMatchingRule().getOID()))
          {
            LocalizableMessage message =
                    WARN_CONFIG_SCHEMA_CANNOT_DELETE_MR_IN_USE_BY_MRU.get(
                            matchingRule.getNameOrOID(), mru.getNameOrOID());
            unacceptableReasons.add(message);

            configAcceptable = false;
            continue;
          }
        }
      }
    }

    return configAcceptable;
  }



  /**
   * {@inheritDoc}
   */
  public ConfigChangeResult applyConfigurationDelete(
                                 MatchingRuleCfg configuration)
  {
    ResultCode        resultCode          = ResultCode.SUCCESS;
    boolean           adminActionRequired = false;
    ArrayList<LocalizableMessage> messages            = new ArrayList<LocalizableMessage>();

    MatchingRuleFactory<?> factory =
            matchingRuleFactories.remove(configuration.dn());
    if (factory != null)
    {
      for(MatchingRule matchingRule: factory.getMatchingRules())
      {
        DirectoryServer.deregisterMatchingRule(matchingRule);
      }
      factory.finalizeMatchingRule();
    }

    return new ConfigChangeResult(resultCode, adminActionRequired, messages);
  }



  /**
   * {@inheritDoc}
   */
  public boolean isConfigurationChangeAcceptable(MatchingRuleCfg configuration,
                      List<LocalizableMessage> unacceptableReasons)
  {
    boolean configAcceptable = true;
    if (configuration.isEnabled())
    {
      // Get the name of the class and make sure we can instantiate it as a
      // matching rule Factory.
      String className = configuration.getJavaClass();
      try
      {
        loadMatchingRuleFactory(className, configuration, false);
      }
      catch (InitializationException ie)
      {
        unacceptableReasons.add(ie.getMessageObject());
        configAcceptable = false;
      }
    }
    else
    {
      // If the matching rule is currently enabled and the change would make it
      // disabled, then only allow it if the matching rule isn't already in use.
      MatchingRuleFactory<?> factory =
              matchingRuleFactories.get(configuration.dn());
      if(factory == null)
      {
        //Factory was disabled again.
        return configAcceptable;
      }
      for(MatchingRule matchingRule: factory.getMatchingRules())
      {
        if (matchingRule != null)
        {
          String oid = matchingRule.getOID();
          for (AttributeType at : DirectoryServer.getAttributeTypes().values())
          {
            MatchingRule amr = at.getApproximateMatchingRule();
            if ((amr != null) && oid.equals(amr.getOID()))
            {
              LocalizableMessage message =
                      WARN_CONFIG_SCHEMA_CANNOT_DISABLE_MR_IN_USE_BY_AT.get(
                              matchingRule.getNameOrOID(), at.getNameOrOID());
              unacceptableReasons.add(message);

              configAcceptable = false;
              continue;
            }

            MatchingRule emr = at.getEqualityMatchingRule();
            if ((emr != null) && oid.equals(emr.getOID()))
            {
              LocalizableMessage message =
                      WARN_CONFIG_SCHEMA_CANNOT_DISABLE_MR_IN_USE_BY_AT.get(
                              matchingRule.getNameOrOID(), at.getNameOrOID());
              unacceptableReasons.add(message);

              configAcceptable = false;
              continue;
            }

            MatchingRule omr = at.getOrderingMatchingRule();
            if ((omr != null) && oid.equals(omr.getOID()))
            {
              LocalizableMessage message =
                      WARN_CONFIG_SCHEMA_CANNOT_DISABLE_MR_IN_USE_BY_AT.get(
                              matchingRule.getNameOrOID(), at.getNameOrOID());
              unacceptableReasons.add(message);

              configAcceptable = false;
              continue;
            }

            SubstringMatchingRule smr = at.getSubstringMatchingRule();
            if ((smr != null) && oid.equals(smr.getOID()))
            {
              LocalizableMessage message =
                      WARN_CONFIG_SCHEMA_CANNOT_DISABLE_MR_IN_USE_BY_AT
                      .get(matchingRule.getNameOrOID(), at.getNameOrOID());
              unacceptableReasons.add(message);

              configAcceptable = false;
              continue;
            }
          }

          for (MatchingRuleUse mru :
               DirectoryServer.getMatchingRuleUses().values())
          {
            if (oid.equals(mru.getMatchingRule().getOID()))
            {
              LocalizableMessage message =
                      WARN_CONFIG_SCHEMA_CANNOT_DISABLE_MR_IN_USE_BY_MRU.get(
                              matchingRule.getNameOrOID(), mru.getNameOrOID());
              unacceptableReasons.add(message);

              configAcceptable = false;
              continue;
            }
          }
        }
      }
    }
    return configAcceptable;
  }



  /**
   * {@inheritDoc}
   */
  public ConfigChangeResult applyConfigurationChange(
                                 MatchingRuleCfg configuration)
  {
    ResultCode        resultCode          = ResultCode.SUCCESS;
    boolean           adminActionRequired = false;
    ArrayList<LocalizableMessage> messages            = new ArrayList<LocalizableMessage>();


   // Get the existing matching rule factory if it's already enabled.
    MatchingRuleFactory<?> existingFactory =
            matchingRuleFactories.get(configuration.dn());


    // If the new configuration has the matching rule disabled, then disable it
    // if it is enabled, or do nothing if it's already disabled.
    if (! configuration.isEnabled())
    {
     if (existingFactory != null)
      {
        for(MatchingRule existingRule: existingFactory.getMatchingRules())
        {
          DirectoryServer.deregisterMatchingRule(existingRule);
        }
        matchingRuleFactories.remove(configuration.dn());
        existingFactory.finalizeMatchingRule();
      }
      return new ConfigChangeResult(resultCode, adminActionRequired, messages);
    }


    // Get the class for the matching rule.  If the matching rule is already
    // enabled, then we shouldn't do anything with it although if the class has
    // changed then we'll at least need to indicate that administrative action
    // is required.  If the matching rule is disabled, then instantiate the
    // class and initialize and register it as a matching rule.
    String className = configuration.getJavaClass();
    if (existingFactory != null)
    {
      if (! className.equals(existingFactory.getClass().getName()))
      {
        adminActionRequired = true;
      }

      return new ConfigChangeResult(resultCode, adminActionRequired, messages);
    }

    MatchingRuleFactory<?> factory = null;
    try
    {
      factory = loadMatchingRuleFactory(className, configuration, true);

      try
      {
        for(MatchingRule matchingRule: factory.getMatchingRules())
        {
          DirectoryServer.registerMatchingRule(matchingRule, false);
        }
        matchingRuleFactories.put(configuration.dn(), factory);
      }
      catch (DirectoryException de)
      {
        messages.add(WARN_CONFIG_SCHEMA_MR_CONFLICTING_MR.get(configuration.dn(), de.getMessageObject()));

        if (resultCode == ResultCode.SUCCESS)
        {
          resultCode = DirectoryServer.getServerErrorResultCode();
        }
      }
    }
    catch (InitializationException ie)
    {
      if (resultCode == ResultCode.SUCCESS)
      {
        resultCode = DirectoryServer.getServerErrorResultCode();
      }

      messages.add(ie.getMessageObject());
    }

    return new ConfigChangeResult(resultCode, adminActionRequired, messages);
  }



  /**
   * Loads the specified class, instantiates it as an attribute syntax, and
   * optionally initializes that instance.
   *
   * @param  className      The fully-qualified name of the attribute syntax
   *                        class to load, instantiate, and initialize.
   * @param  configuration  The configuration to use to initialize the attribute
   *                        syntax.  It must not be {@code null}.
   * @param  initialize     Indicates whether the matching rule instance should
   *                        be initialized.
   *
   * @return  The possibly initialized attribute syntax.
   *
   * @throws  InitializationException  If a problem occurred while attempting to
   *                                   initialize the attribute syntax.
   */
  private MatchingRuleFactory loadMatchingRuleFactory(String className,
                                        MatchingRuleCfg configuration,
                                        boolean initialize)
          throws InitializationException
  {
    try
    {
      MatchingRuleFactory factory = null;
      MatchingRuleCfgDefn definition =
              MatchingRuleCfgDefn.getInstance();
      ClassPropertyDefinition propertyDefinition =
           definition.getJavaClassPropertyDefinition();
      Class<? extends MatchingRuleFactory> matchingRuleFactoryClass =
           propertyDefinition.loadClass(className,
                                        MatchingRuleFactory.class);
      factory = matchingRuleFactoryClass.newInstance();

      if (initialize)
      {
        factory.initializeMatchingRule(configuration);
      }
      else
      {
        List<LocalizableMessage> unacceptableReasons = new ArrayList<LocalizableMessage>();
        if (!factory.isConfigurationAcceptable(configuration, unacceptableReasons))
        {
          String reasons = Utils.joinAsString(".  ", unacceptableReasons);
          throw new InitializationException(
              ERR_CONFIG_SCHEMA_MR_CONFIG_NOT_ACCEPTABLE.get(configuration.dn(), reasons));
        }
      }

      return factory;
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_CONFIG_SCHEMA_MR_CANNOT_INITIALIZE.
          get(className, configuration.dn(), stackTraceToSingleLineString(e));
      throw new InitializationException(message, e);
    }
  }
}
