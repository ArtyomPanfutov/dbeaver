/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
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
package org.jkiss.dbeaver.tools.transfer.ui.wizard;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ui.IExportWizard;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.runtime.DBRRunnableContext;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizard;
import org.jkiss.dbeaver.tasks.ui.wizard.TaskConfigurationWizardDialog;
import org.jkiss.dbeaver.tools.transfer.*;
import org.jkiss.dbeaver.tools.transfer.internal.DTMessages;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferNodeDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.ui.internal.DTUIActivator;
import org.jkiss.dbeaver.tools.transfer.ui.registry.DataTransferConfiguratorRegistry;
import org.jkiss.dbeaver.tools.transfer.ui.registry.DataTransferNodeConfiguratorDescriptor;
import org.jkiss.dbeaver.tools.transfer.ui.registry.DataTransferPageDescriptor;
import org.jkiss.dbeaver.tools.transfer.ui.registry.DataTransferPageType;
import org.jkiss.dbeaver.ui.DialogSettingsMap;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.*;

public class DataTransferWizard extends TaskConfigurationWizard implements IExportWizard, IImportWizard {

    private static final String RS_EXPORT_WIZARD_DIALOG_SETTINGS = "DataTransfer";//$NON-NLS-1$
    private static final Log log = Log.getLog(DataTransferWizard.class);

    public static void openWizard(
        @NotNull IWorkbenchWindow workbenchWindow,
        @Nullable Collection<IDataTransferProducer> producers,
        @Nullable Collection<IDataTransferConsumer> consumers)
    {
        DataTransferWizard wizard = new DataTransferWizard(UIUtils.getDefaultRunnableContext(), producers, consumers, null);
        TaskConfigurationWizardDialog dialog = new TaskConfigurationWizardDialog(workbenchWindow, wizard);
        dialog.open();
    }

    public static void openWizard(
        @NotNull IWorkbenchWindow workbenchWindow,
        @Nullable Collection<IDataTransferProducer> producers,
        @Nullable Collection<IDataTransferConsumer> consumers,
        @Nullable IStructuredSelection selection)
    {
        DataTransferWizard wizard = new DataTransferWizard(UIUtils.getDefaultRunnableContext(), producers, consumers, null);
        TaskConfigurationWizardDialog dialog = new TaskConfigurationWizardDialog(workbenchWindow, wizard, selection);
        dialog.open();
    }

    public static int openWizard(
        @NotNull IWorkbenchWindow workbenchWindow,
        @NotNull DBTTask task)
    {
        DataTransferWizard wizard = new DataTransferWizard(UIUtils.getDefaultRunnableContext(), task);
        TaskConfigurationWizardDialog dialog = new TaskConfigurationWizardDialog(workbenchWindow, wizard, null);
        return dialog.open();
    }

    //private static final Log log = Log.getLog(DataTransferWizard.class);

    static class NodePageSettings {
        DataTransferNodeDescriptor sourceNode;
        DataTransferNodeConfiguratorDescriptor nodeConfigurator;
        IWizardPage[] pages;
        IWizardPage settingsPage;

        private NodePageSettings(DataTransferNodeDescriptor sourceNode, DataTransferNodeConfiguratorDescriptor nodeConfigurator, boolean consumerOptional, boolean producerOptional) {
            this.sourceNode = sourceNode;
            this.nodeConfigurator = nodeConfigurator;
            this.pages = nodeConfigurator == null ? new IWizardPage[0] : nodeConfigurator.createWizardPages(consumerOptional, producerOptional, false);
            IWizardPage[] sPages = nodeConfigurator == null ? new IWizardPage[0] : nodeConfigurator.createWizardPages(consumerOptional, producerOptional, true);
            // There can be only one settings page per node
            this.settingsPage = sPages.length == 0 ? null : sPages[0];
        }

    }

    private DataTransferSettings settings;
    private Map<Class, NodePageSettings> nodeSettings = new LinkedHashMap<>();

    private DataTransferWizard(@Nullable DBTTask task) {
        super(task);
        setDialogSettings(
            UIUtils.getSettingsSection(
                DTUIActivator.getDefault().getDialogSettings(),
                RS_EXPORT_WIZARD_DIALOG_SETTINGS));
    }

    public DataTransferWizard(@NotNull DBRRunnableContext runnableContext, DBTTask task) {
        this(task);
        this.settings = new DataTransferSettings(runnableContext, task, log);
        loadSettings();
    }

    private DataTransferWizard(@NotNull DBRRunnableContext runnableContext, @Nullable Collection<IDataTransferProducer> producers, @Nullable Collection<IDataTransferConsumer> consumers, @Nullable DBTTask task) {
        this(task);
        this.settings = new DataTransferSettings(runnableContext, producers, consumers, new DialogSettingsMap(getDialogSettings()));

        loadSettings();
    }

    void loadSettings() {
        // Load node settings
        Collection<DBSObject> objectTypes = settings.getSourceObjects();
        List<DataTransferNodeDescriptor> nodes = new ArrayList<>();
        DataTransferRegistry registry = DataTransferRegistry.getInstance();
        if (ArrayUtils.isEmpty(settings.getInitProducers())) {
            nodes.addAll(registry.getAvailableProducers(objectTypes));
        } else {
            for (IDataTransferProducer source : settings.getInitProducers()) {
                DataTransferNodeDescriptor node = registry.getNodeByType(source.getClass());
                if (node != null && !nodes.contains(node)) {
                    nodes.add(node);
                }
            }
        }
        if (ArrayUtils.isEmpty(settings.getInitConsumers())) {
            nodes.addAll(registry.getAvailableConsumers(objectTypes));
        } else {
            for (IDataTransferConsumer target : settings.getInitConsumers()) {
                DataTransferNodeDescriptor node = registry.getNodeByType(target.getClass());
                if (node != null && !nodes.contains(node)) {
                    nodes.add(node);
                }
            }
        }

        boolean settingsChanged = nodeSettings.size() != nodes.size();
        if (!settingsChanged) {
            List<NodePageSettings> nsList = new ArrayList<>(nodeSettings.values());
            for (int i = 0; i < nodeSettings.size(); i++) {
                if (nsList.get(i).sourceNode != nodes.get(i)) {
                    settingsChanged = true;
                    break;
                }
            }
        }

        if (settingsChanged) {
            nodeSettings.clear();
            for (DataTransferNodeDescriptor node : nodes) {
                addNodeSettings(node);
            }
        }

        updateWizardTitle();
    }

    @Override
    public String getTaskTypeId() {
        if (getSettings().isProducerOptional()) {
            return DTConstants.TASK_IMPORT;
        } else {
            return DTConstants.TASK_EXPORT;
        }
    }

    public DataTransferSettings getSettings() {
        return settings;
    }

    public <T extends IDataTransferSettings> T getPageSettings(IWizardPage page, Class<T> type) {
        return type.cast(getNodeSettings(page));
    }

    @Override
    public void addPages() {
        super.addPages();
        if (settings.isConsumerOptional() || settings.isProducerOptional()) {
            addPage(new DataTransferPagePipes());
        }
        addWizardPages(this);
        addPage(new DataTransferPageFinal());
    }

    @Override
    protected String getDefaultWindowTitle() {
        return DTMessages.data_transfer_wizard_name;
    }

    @Nullable
    @Override
    public IWizardPage getNextPage(IWizardPage page) {
        IWizardPage[] pages = getPages();
        int curIndex = -1;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == page) {
                curIndex = i;
                break;
            }
        }
        if (curIndex == pages.length - 1) {
            return null;
        }
        if (curIndex != -1) {
            // Return first node config page
            for (int i = curIndex + 1; i < pages.length; i++) {
                if (isPageValid(pages[i])) {
                    return pages[i];
                }
            }
        }
        // Final page
        return pages[pages.length - 1];
    }

    @Nullable
    @Override
    public IWizardPage getPreviousPage(IWizardPage page) {
        IWizardPage[] pages = getPages();
        int curIndex = -1;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == page) {
                curIndex = i;
                break;
            }
        }
        if (curIndex == 0) {
            return null;
        }
        if (curIndex != -1) {
            for (int i = curIndex - 1; i > 0; i--) {
                if (isPageValid(pages[i])) {
                    return pages[i];
                }
            }
        }
        // First page
        return pages[0];
    }

    @Override
    public boolean performCancel() {
        // Save settings if we have task
        if (getCurrentTask() != null) {
            saveDialogSettings();
        }

        return super.performCancel();
    }

    @Override
    public boolean performFinish() {
        saveDialogSettings();

        return super.performFinish();
    }

    // Saves wizard settings in UI dialog settings
    private void saveDialogSettings() {
        // Save settings
        DialogSettingsMap dialogSettings = new DialogSettingsMap(getDialogSettings());
        saveConfiguration(dialogSettings);

        DTUIActivator.getDefault().saveDialogSettings();
    }

    @Override
    public void setContainer(IWizardContainer wizardContainer) {
        super.setContainer(wizardContainer);
        //wizardContainer.
    }

    private void addNodeSettings(DataTransferNodeDescriptor node) {
        if (node == null) {
            return;
        }
        Class<? extends IDataTransferNode> nodeClass = node.getNodeClass();
        if (nodeSettings.containsKey(nodeClass)) {
            return;
        }

        DataTransferNodeConfiguratorDescriptor configurator = DataTransferConfiguratorRegistry.getInstance().getConfigurator(node.getId());
        nodeSettings.put(nodeClass, new NodePageSettings(node, configurator, settings.isConsumerOptional(), settings.isProducerOptional()));
    }

    private void addWizardPages(DataTransferWizard wizard) {
        List<IWizardPage> settingPages = new ArrayList<>();
        // Add regular pages
        for (NodePageSettings nodePageSettings : this.nodeSettings.values()) {
            for (IWizardPage page : nodePageSettings.pages) {
                if (nodePageSettings.nodeConfigurator == null || nodePageSettings.nodeConfigurator.getPageDescriptor(page).getPageType() == DataTransferPageType.PREVIEW) {
                    // Add later
                    continue;
                }
                wizard.addPage(page);
            }
            if (nodePageSettings.settingsPage != null) {
                settingPages.add(nodePageSettings.settingsPage);
            }
        }
        // Add common settings page
        if (!CommonUtils.isEmpty(settingPages)) {
            wizard.addPage(new DataTransferPageSettings());
        }
        // Add preview pages
        for (NodePageSettings nodePageSettings : this.nodeSettings.values()) {
            for (IWizardPage page : nodePageSettings.pages) {
                if (nodePageSettings.nodeConfigurator != null && nodePageSettings.nodeConfigurator.getPageDescriptor(page).getPageType() == DataTransferPageType.PREVIEW) {
                    wizard.addPage(page);
                }
            }
        }
    }

    protected boolean isPageValid(IWizardPage page) {
        return page instanceof DataTransferPagePipes ||
            page instanceof DataTransferPageSettings ||
            page instanceof DataTransferPageFinal ||
            isPageValid(page, settings.getProducer()) ||
            isPageValid(page, settings.getConsumer());
    }

    private boolean isPageValid(IWizardPage page, DataTransferNodeDescriptor node) {
        NodePageSettings nodePageSettings = node == null ? null : this.nodeSettings.get(node.getNodeClass());
        if (nodePageSettings != null && ArrayUtils.contains(nodePageSettings.pages, page)) {
            // Check does page matches consumer and producer
            for (NodePageSettings ns : this.nodeSettings.values()) {
                DataTransferPageDescriptor pd = ns.nodeConfigurator == null ? null : ns.nodeConfigurator.getPageDescriptor(page);
                if (pd != null) {
                    if (pd.getProducerType() != null && settings.getProducer() != null && !settings.getProducer().getId().equals(pd.getProducerType())) {
                        // Producer doesn't match
                        return false;
                    }
                    if (pd.getConsumerType() != null && settings.getConsumer() != null && !settings.getConsumer().getId().equals(pd.getConsumerType())) {
                        // Consumer doesn't match
                        return false;
                    }
                }
            }

            return true;
        }
        return false;
    }

    NodePageSettings getNodeInfo(IDataTransferNode node) {
        return this.nodeSettings.get(node.getClass());
    }

    private IDataTransferSettings getNodeSettings(IWizardPage page) {
        for (NodePageSettings nodePageSettings : this.nodeSettings.values()) {
            if (page == nodePageSettings.settingsPage) {
                return settings.getNodeSettings(nodePageSettings.sourceNode);
            }
            if (nodePageSettings.pages != null) {
                for (IWizardPage nodePage : nodePageSettings.pages) {
                    if (nodePage == page) {
                        return settings.getNodeSettings(nodePageSettings.sourceNode);
                    }
                }
            }
        }
        return null;
    }

    public void saveTaskState(DBRRunnableContext runnableContext, Map<String, Object> state) {
        List<IDataTransferNode> producers = new ArrayList<>();
        List<IDataTransferNode> consumers = new ArrayList<>();
        for (DataTransferPipe pipe : settings.getDataPipes()) {
            if (pipe.getProducer() != null) {
                producers.add(pipe.getProducer());
            }
            if (pipe.getConsumer() != null) {
                consumers.add(pipe.getConsumer());
            }
        }
        DataTransferSettings.saveNodesLocation(runnableContext, state, producers, "producers");
        DataTransferSettings.saveNodesLocation(runnableContext, state, consumers, "consumers");
        state.put("configuration", saveConfiguration(new LinkedHashMap<>()));
    }

    @Override
    public boolean isRunTaskOnFinish() {
        return true;
    }

    private Map<String, Object> saveConfiguration(Map<String, Object> config) {
        config.put("maxJobCount", settings.getMaxJobCount());
        config.put("showFinalMessage", settings.isShowFinalMessage());
        // Save nodes' settings
        for (Map.Entry<Class, NodePageSettings> entry : nodeSettings.entrySet()) {
            //IDialogSettings nodeSection = DialogSettings.getOrCreateSection(dialogSettings, entry.getKey().getSimpleName());
            Map<String, Object> nodeSection = new LinkedHashMap<>();

            IDataTransferSettings settings = this.settings.getNodeSettings(entry.getValue().sourceNode);
            if (settings != null) {
                settings.saveSettings(nodeSection);
            }
            // Note: do it in the end because of limitation of IDialogSettings wrapper
            config.put(entry.getKey().getSimpleName(), nodeSection);
        }

        if (settings.getProducer() != null) {
            config.put("producer", settings.getProducer().getId());
        }
        if (settings.getConsumer() != null) {
            config.put("consumer", settings.getConsumer().getId());
        }
        if (settings.getProcessor() != null) {
            config.put("processor", settings.getProcessor().getId());
        }

        // Save processors' properties
        Map<String, Object> processorsSection = new LinkedHashMap<>();

        for (DataTransferProcessorDescriptor procDescriptor : settings.getProcessorPropsHistory().keySet()) {

            Map<String, Object> procSettings = new LinkedHashMap<>();

            Map<Object, Object> props = settings.getProcessorPropsHistory().get(procDescriptor);
            if (!CommonUtils.isEmpty(props)) {
                StringBuilder propNames = new StringBuilder();
                for (Map.Entry<Object, Object> prop : props.entrySet()) {
                    propNames.append(prop.getKey()).append(',');
                }
                procSettings.put("@propNames", propNames.toString());
                for (Map.Entry<Object, Object> prop : props.entrySet()) {
                    procSettings.put(CommonUtils.toString(prop.getKey()), CommonUtils.toString(prop.getValue()));
                }
            }
            processorsSection.put(procDescriptor.getFullId(), procSettings);
        }
        config.put("processors", processorsSection);

        return config;
    }

}