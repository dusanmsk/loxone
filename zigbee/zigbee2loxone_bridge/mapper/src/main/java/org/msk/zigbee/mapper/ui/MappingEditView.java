package org.msk.zigbee.mapper.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Label;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.annotation.SpringComponent;
import com.vaadin.flow.spring.annotation.UIScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.apache.commons.lang3.SerializationUtils;
import org.msk.zigbee.mapper.ConfigurationService;
import org.msk.zigbee.mapper.LoxoneService;
import org.msk.zigbee.mapper.TemplatingService;
import org.msk.zigbee.mapper.ZigbeeService;
import org.msk.zigbee.mapper.configs.Configuration;

@SpringComponent
@Route
@UIScope
public class MappingEditView extends VerticalLayout {

    private final LoxoneService loxoneService;
    private final TemplatingService templatingService;
    private final ZigbeeService zigbeeService;
    private final ObjectMapper objectMapper;
    private final ConfigurationService configurationService;

    private TextField deviceNameTextField;
    private Grid<Configuration.Mapping.PayloadMapping> payloadMappingGrid;
    ComboBox<Configuration.Mapping.Direction> directionComboBox = new ComboBox<>();

    private Configuration.Mapping mapping;

    private String zigbeeDeviceName;

    public MappingEditView(LoxoneService loxoneService, TemplatingService templatingService, ZigbeeService zigbeeService, ObjectMapper objectMapper,
            ConfigurationService configurationService) {
        this.loxoneService = loxoneService;
        this.zigbeeService = zigbeeService;
        this.templatingService = templatingService;
        this.configurationService = configurationService;
        this.objectMapper = objectMapper;
        deviceNameTextField = new TextField("Zigbee device name:");
        deviceNameTextField.setReadOnly(true);

        directionComboBox.setItems(Configuration.Mapping.Direction.values());
        directionComboBox.setRequired(true);
        directionComboBox.addValueChangeListener(i -> mapping.setDirection(i.getValue()));

        HorizontalLayout buttonLayout = new HorizontalLayout();
        buttonLayout.add(new Button("Create new", this::createNewPayloadMapping));
        buttonLayout.add(new Button("Clone existing", this::cloneExistingPayloadMapping));

        payloadMappingGrid = createPayloadMappingGrid();

        add(deviceNameTextField, directionComboBox);
        add(new Label("Payload mapping:"), buttonLayout, payloadMappingGrid);

        add(new Button("Save", this::save));
        add(new Button("Cancel", this::cancel));
    }

    private void cloneExistingPayloadMapping(ClickEvent<Button> buttonClickEvent) {
        Dialog cloneDialog = new Dialog();
        ComboBox<String> deviceNameComboBox = new ComboBox<>();
        deviceNameComboBox.setItems(configurationService.getMappedZigbeeDeviceNames());
        cloneDialog.add(deviceNameComboBox);
        cloneDialog.add(new Button("Confirm", event -> {
            cloneMappingFrom(deviceNameComboBox.getValue());
            cloneDialog.close();
        }));
        cloneDialog.open();
    }

    private void cloneMappingFrom(String sourceZigbeeDeviceName) {
        var origName = mapping.getZigbeeDeviceName();
        configurationService.getMapping(sourceZigbeeDeviceName).ifPresent(m -> {
            m = SerializationUtils.clone(m);
            m.setZigbeeDeviceName(origName);
            editMapping(sourceZigbeeDeviceName, m);
        });
    }

    private void cancel(ClickEvent<Button> buttonClickEvent) {
        UI.getCurrent().navigate(MainView.class);
    }

    private void save(ClickEvent<Button> buttonClickEvent) {
        configurationService.setMapping(mapping.getZigbeeDeviceName(), mapping);
        UI.getCurrent().navigate(MainView.class);
    }

    private void createNewPayloadMapping(ClickEvent<Button> buttonClickEvent) {
        Configuration.Mapping.PayloadMapping payloadMapping = Configuration.Mapping.PayloadMapping.builder().build();
        editPayloadMapping(payloadMapping);
    }

    public void editMapping(String zigbeeDeviceName, Configuration.Mapping mappingToEdit) {
        this.zigbeeDeviceName = zigbeeDeviceName;
        this.mapping = SerializationUtils.clone(mappingToEdit);
        refreshData();
    }

    private void refreshData() {
        deviceNameTextField.setValue(mapping.getZigbeeDeviceName());
        directionComboBox.setValue(mapping.getDirection());
        List<Configuration.Mapping.PayloadMapping> payloadMapping = mapping.getPayloadMapping();
        if (payloadMapping == null) {
            payloadMapping = new ArrayList<>();
        }
        payloadMappingGrid.setItems(payloadMapping);
    }

    private Grid<Configuration.Mapping.PayloadMapping> createPayloadMappingGrid() {
        Grid<Configuration.Mapping.PayloadMapping> grid = new Grid<>();
        grid.addColumn(Configuration.Mapping.PayloadMapping::getLoxoneComponentName).setHeader("Loxone component name");
        grid.addColumn(Configuration.Mapping.PayloadMapping::getLoxoneAttributeName).setHeader("Loxone attribute name");
        grid.addColumn(Configuration.Mapping.PayloadMapping::getZigbeeAttributeName).setHeader("Zigbee attribute name");
        //grid.addItemDoubleClickListener(e -> editPayloadMapping(e.getItem()));
        grid.addComponentColumn(i -> createEditDeleteButtons(i));
        return grid;
    }

    private Component createEditDeleteButtons(Configuration.Mapping.PayloadMapping payloadMapping) {
        HorizontalLayout horizontalLayout = new HorizontalLayout();
        horizontalLayout.add(new Button("Edit", x -> {
            editPayloadMapping(payloadMapping);
        }));
        horizontalLayout.add(new Button("Delete", x -> {
            deletePayloadMapping(payloadMapping);
        }));
        return horizontalLayout;
    }

    private void deletePayloadMapping(Configuration.Mapping.PayloadMapping payloadMapping) {
        mapping.getPayloadMapping().remove(payloadMapping);
        refreshData();
    }

    private void editPayloadMapping(Configuration.Mapping.PayloadMapping toEditMapping) {
        PayloadMappingEditDialog dialog =
                new PayloadMappingEditDialog(mapping.getZigbeeDeviceName(), toEditMapping, loxoneService, zigbeeService, templatingService) {

                    @Override
                    public void onSave(Configuration.Mapping.PayloadMapping editedMapping) {
                        int idx = mapping.getPayloadMapping().indexOf(toEditMapping);
                        idx = idx == -1 ? 0 : idx;
                        mapping.getPayloadMapping().remove(toEditMapping);
                        mapping.getPayloadMapping().add(idx, editedMapping);
                        refreshData();
                    }
                };
        add(dialog.dialog);
    }

    abstract class PayloadMappingEditDialog {

        private final Dialog dialog;
        private ComboBox<String> loxoneComponentNameCombobox;
        private ComboBox<String> loxoneAttributeNameComboBox;
        private ComboBox<String> zigbeeAttributeNameComboBox;

        private final ComboBox.ItemFilter<String> simpleFilter = (i, filterString) -> i == null ? false : i.toLowerCase().contains(filterString.toLowerCase());

        public abstract void onSave(Configuration.Mapping.PayloadMapping payloadMapping);

        public PayloadMappingEditDialog(String zigbeeDeviceName, Configuration.Mapping.PayloadMapping payloadMapping, LoxoneService loxoneService,
                ZigbeeService zigbeeService, TemplatingService templatingService) {
            dialog = new Dialog();
            dialog.setResizable(true);
            dialog.setHeaderTitle("Edit mapping");
            VerticalLayout dialogLayout = new VerticalLayout();

            HashSet<String> knownLoxoneComponentNames = new HashSet<>(loxoneService.getKnownLoxoneComponentNames());
            knownLoxoneComponentNames.add(payloadMapping.getLoxoneComponentName());

            HashSet<String> knownLoxoneAttributeNames = new HashSet<>();
            knownLoxoneAttributeNames.add(payloadMapping.getLoxoneAttributeName());

            HashSet<String> knownZigbeeAttributeNames = new HashSet<>(zigbeeService.getKnownAttributeNames(zigbeeDeviceName));
            knownZigbeeAttributeNames.add(payloadMapping.getZigbeeAttributeName());

            loxoneComponentNameCombobox = new ComboBox<>("Loxone component name:");
            loxoneComponentNameCombobox.setRequired(true);
            allowCustomValues(loxoneComponentNameCombobox, knownLoxoneComponentNames, simpleFilter);
            loxoneComponentNameCombobox.setSizeFull();

            loxoneAttributeNameComboBox = new ComboBox<>("Loxone attribute name:");
            loxoneAttributeNameComboBox.setRequired(true);
            allowCustomValues(loxoneAttributeNameComboBox, knownLoxoneAttributeNames, simpleFilter);
            loxoneAttributeNameComboBox.setSizeFull();

            zigbeeAttributeNameComboBox = new ComboBox<>("Zigbee attribute name:");
            zigbeeAttributeNameComboBox.setRequired(true);
            allowCustomValues(zigbeeAttributeNameComboBox, knownZigbeeAttributeNames, simpleFilter);
            zigbeeAttributeNameComboBox.setSizeFull();

            loxoneComponentNameCombobox.addValueChangeListener(i -> {
                knownLoxoneAttributeNames.clear();
                knownLoxoneAttributeNames.addAll(loxoneService.getAllKnownAttributes(i.getValue()));
                loxoneAttributeNameComboBox.setItems(simpleFilter, knownLoxoneAttributeNames);
            });

            TextField l2zMappingFormulaEditor = createMappingFormulaEditor("Loxone to zigbee mapping formula:");
            TextField z2lMappingFormulaEditor = createMappingFormulaEditor("Zigbee to loxone mapping formula:");

            loxoneComponentNameCombobox.setItems(simpleFilter, knownLoxoneComponentNames);
            loxoneComponentNameCombobox.setValue(payloadMapping.getLoxoneComponentName());

            loxoneAttributeNameComboBox.setItems(simpleFilter, knownLoxoneAttributeNames);
            loxoneAttributeNameComboBox.setValue(payloadMapping.getLoxoneAttributeName());

            zigbeeAttributeNameComboBox.setItems(simpleFilter, knownZigbeeAttributeNames);
            zigbeeAttributeNameComboBox.setValue(payloadMapping.getZigbeeAttributeName());

            if (payloadMapping.getMappingFormulaL2Z() != null) {
                l2zMappingFormulaEditor.setValue(payloadMapping.getMappingFormulaL2Z());
            }
            if (payloadMapping.getMappingFormulaZ2L() != null) {
                z2lMappingFormulaEditor.setValue(payloadMapping.getMappingFormulaZ2L());
            }

            SamplePayloadSelector loxoneToZigbeePayloadSampleSelector =
                    new SamplePayloadSelector(loxoneService.getPayloadSamples(payloadMapping.getLoxoneComponentName())) {

                        @Override
                        public String translateValue(String payload) {
                            return templatingService.processTemplate(l2zMappingFormulaEditor.getValue(), payload, loxoneAttributeNameComboBox.getValue());
                        }

                        @Override
                        public void send(String value) {
                            zigbeeService.send(zigbeeDeviceName, zigbeeAttributeNameComboBox.getValue(), value);
                        }
                    };

            SamplePayloadSelector zigbeeToLoxonePayloadSampleSelector = new SamplePayloadSelector(zigbeeService.getPayloadSamples(zigbeeDeviceName)) {

                @Override
                public String translateValue(String payload) {
                    return templatingService.processTemplate(z2lMappingFormulaEditor.getValue(), payload, zigbeeAttributeNameComboBox.getValue());
                }

                @Override
                public void send(String value) {
                    loxoneService.send(loxoneComponentNameCombobox.getValue(), value);
                }
            };

            dialogLayout.add(loxoneComponentNameCombobox, loxoneAttributeNameComboBox, zigbeeAttributeNameComboBox, l2zMappingFormulaEditor,
                    z2lMappingFormulaEditor, loxoneToZigbeePayloadSampleSelector, zigbeeToLoxonePayloadSampleSelector);
            dialog.setWidth("40%");
            dialog.setResizable(true);
            dialog.add(dialogLayout);

            Button saveButton = new Button("Save", e -> {
                Configuration.Mapping.PayloadMapping m = Configuration.Mapping.PayloadMapping.builder()
                        .loxoneAttributeName(loxoneAttributeNameComboBox.getValue())
                        .loxoneComponentName(loxoneComponentNameCombobox.getValue())
                        .mappingFormulaL2Z(l2zMappingFormulaEditor.getValue())
                        .mappingFormulaZ2L(z2lMappingFormulaEditor.getValue())
                        .zigbeeAttributeName(zigbeeAttributeNameComboBox.getValue())
                        .build();
                onSave(m);
                dialog.close();
            });
            Button cancelButton = new Button("Cancel", e -> dialog.close());
            dialog.getFooter().add(cancelButton);
            dialog.getFooter().add(saveButton);

            //dialog.setModal(true);
            dialog.open();
        }

        private void allowCustomValues(ComboBox<String> comboBox, HashSet<String> items, ComboBox.ItemFilter<String> filter) {
            comboBox.setAllowCustomValue(true);
            comboBox.addCustomValueSetListener(i -> {
                items.add(i.getDetail());
                comboBox.setItems(filter, items);
                comboBox.setValue(i.getDetail());
            });
        }

        private TextField createMappingFormulaEditor(String title) {
            TextField editor = new TextField(title);
            editor.setSizeFull();
            return editor;
        }

    }

    private abstract class SamplePayloadSelector extends HorizontalLayout {

        private final ArrayList<String> payloads;
        private final TextField translatedValueTextField;
        private TextField payloadLabel;
        int cursor = 0;

        public SamplePayloadSelector(Collection<String> payloads) {
            this.payloads = new ArrayList<>(payloads);
            payloadLabel = new TextField();
            //payloadLabel.setReadOnly(true);
            Button prevButton = new Button("<", this::prev);
            Button nextButton = new Button(">", this::next);
            translatedValueTextField = new TextField();
            //translatedValueTextField.setReadOnly(true);
            Button sendButton = new Button("Send");
            sendButton.addClickListener(e -> send(translatedValueTextField.getValue()));
            add(payloadLabel, prevButton, nextButton, translatedValueTextField, sendButton);
        }

        abstract public String translateValue(String payload);

        abstract public void send(String value);

        private void next(ClickEvent<Button> buttonClickEvent) {
            cursor++;
            update();
        }

        private void prev(ClickEvent<Button> buttonClickEvent) {
            cursor--;
            update();
        }

        private void update() {
            if (payloads.isEmpty())
                return;
            fixCursor();
            String payload = payloads.get(cursor);
            payloadLabel.setValue(payload);
            try {
                translatedValueTextField.setValue(translateValue(payload));
            } catch (Exception e) {
                translatedValueTextField.setValue("ERROR");
                Notification.show(e.toString());
            }
        }

        private void fixCursor() {
            if (cursor > payloads.size() - 1) {
                cursor = payloads.size() - 1;
            }
            if (cursor < 0) {
                cursor = 0;
            }
        }

    }
}
