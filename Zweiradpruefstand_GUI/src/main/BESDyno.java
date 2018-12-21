package main;

import data.Bike;
import data.Config;
import development.CommunicationLogger;
import gui.AboutDialog;
import gui.HelpDialog;
import gui.MeasureDialog;
import gui.SettingsDialog;
import gui.VehicleSetDialog;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.filechooser.FileNameExtensionFilter;
import jssc.SerialPortException;
import logging.LogBackgroundHandler;
import logging.LogOutputStreamHandler;
import logging.Logger;
import serial.ConnectPortWorker;
import serial.requests.Request;
import serial.requests.Request.Status;
import serial.Telegram;

/**
 *
 * @author emil
 */
public class BESDyno extends javax.swing.JFrame {
    
    private static BESDyno instance;

    private static final Logger LOG;
    private static final Logger LOGP;

    //JDialog-Objects
    private AboutDialog about = new AboutDialog(this, false);
    private HelpDialog help = new HelpDialog(this, false);
    private VehicleSetDialog vehicle = new VehicleSetDialog(this, true);
    private MeasureDialog measure = new MeasureDialog(this, true);
    private SettingsDialog settings = new SettingsDialog(this, true);

    //Object-Variables
    private File file;
    private SwingWorker activeWorker;
    private final MyTelegram telegram;
    private jssc.SerialPort port;

    //Variables
    private boolean dark = false;

    public final List<Request> pendingRequests = new LinkedList<>();

    /**
     * Creates new form Gui
     * @return 
     */
    
    public static BESDyno getInstance() {
        if(instance == null) {
            instance = new BESDyno();
        }
        return instance;
    }
    
    private BESDyno() {
        initComponents();

        telegram = new MyTelegram();
        telegram.execute();

        setTitle("BESDyno - Zweiradprüfstand");
        setLocationRelativeTo(null);
        setSize(new Dimension(1200, 750));

        jtfStatus.setEditable(false);
        jtfStatus.setText("Willkommen! Bitte verbinden Sie Ihr Gerät...");
        jmiLogComm.setState(false);

        refreshPorts();

        try {
            loadConfig();
        } catch (Exception ex) {
            userLogPane(ex, "Fehler bei Config-Datei! Bitte Einstellungen aufrufen und Prüfstand konfigurieren!", LogLevel.WARNING);
        }

        refreshGui();

        if (Config.getInstance().isDark()) {
            dark = Config.getInstance().isDark();
            setAppearance(dark);
        }
        jcbmiDarkMode.setState(dark);
    }

    private void refreshGui() {
        jmiSave.setEnabled(false);
        jmiPrint.setEnabled(false);
        jmiStartSim.setEnabled(false);
        jbutStartSim.setEnabled(true);
        jmiConnect.setEnabled(false);
        jbutConnect.setEnabled(false);
        jmiDisconnect.setEnabled(false);
        jbutDisconnect.setEnabled(false);
        jcbSerialDevices.setEnabled(false);
        jcbmiDarkMode.setState(false);
        jmiRefresh.setEnabled(false);
        jbutRefresh.setEnabled(false);

        if (activeWorker != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            return;
        } else {
            setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        }
        jmiRefresh.setEnabled(true);
        jbutRefresh.setEnabled(true);

        //Wennn Ports gefunden werden
        if (jcbSerialDevices.getModel().getSize() > 0) {
            jcbSerialDevices.setEnabled(true);
            jmiConnect.setEnabled(true);
            jbutConnect.setEnabled(true);
        }

        //Wenn ein Port geöffnet wurde
        if (port != null) {
            jbutDisconnect.setEnabled(true);
            jmiDisconnect.setEnabled(true);
            jcbSerialDevices.setEnabled(false);
            jmiRefresh.setEnabled(false);
            jbutRefresh.setEnabled(false);
            jmiConnect.setEnabled(false);
            jbutConnect.setEnabled(false);
            jmiStartSim.setEnabled(true);
            jbutStartSim.setEnabled(true);
        }
    }

    //Status-Textfeld
    
    private enum LogLevel {
        FINEST, FINE, INFO, WARNING, SEVERE
    };

    private void userLog(String msg, LogLevel level) {
        jtfStatus.setText(msg);
        switch (level) {
            case FINEST:
                LOG.finest(msg);
                break;
            case FINE:
                LOG.fine(msg);
                break;
            case INFO:
                LOG.info(msg);
                break;
            case WARNING:
                LOG.warning(msg);
                break;
            case SEVERE:
                LOG.severe(msg);
                break;
        }
    }

    private void userLog(Throwable th, String msg, LogLevel level) {
        jtfStatus.setText(msg);
        switch (level) {
            case FINEST:
                LOG.finest(th);
                break;
            case FINE:
                LOG.fine(th);
                break;
            case INFO:
                LOG.info(th);
                break;
            case WARNING:
                LOG.warning(th);
                break;
            case SEVERE:
                LOG.severe(th);
                break;
        }
    }

    private void userLogPane(String msg, LogLevel level) {
        jtfStatus.setText(msg);
        JOptionPane.showMessageDialog(this, msg, "Fehler ist aufgetreten!", JOptionPane.ERROR_MESSAGE);
        switch (level) {
            case FINEST:
                LOG.finest(msg);
                break;
            case FINE:
                LOG.fine(msg);
                break;
            case INFO:
                LOG.info(msg);
                break;
            case WARNING:
                LOG.warning(msg);
                break;
            case SEVERE:
                LOG.severe(msg);
                break;
        }
    }

    private void userLogPane(Throwable th, String msg, LogLevel level) {
        jtfStatus.setText(msg);
        JOptionPane.showMessageDialog(this, msg, "Fehler ist aufgetreten!", JOptionPane.ERROR_MESSAGE);
        switch (level) {
            case FINEST:
                LOG.finest(th);
                break;
            case FINE:
                LOG.fine(th);
                break;
            case INFO:
                LOG.info(th);
                break;
            case WARNING:
                LOG.warning(th);
                break;
            case SEVERE:
                LOG.severe(th);
                break;
        }
    }
    
    //Serial-Methods
    private void refreshPorts() {
        final String[] ports = jssc.SerialPortList.getPortNames();

        String preferedPort = null;
        for (String p : ports) {
            if (p.contains("USB")) {
                preferedPort = p;
                break;
            }
        }

        jcbSerialDevices.setModel(new DefaultComboBoxModel<String>(ports));
        if (preferedPort != null) {
            jcbSerialDevices.setSelectedItem(preferedPort);
        }

        refreshGui();
    }

    //Mit Ctrl+D kann das Erscheinungbild der Oberfläche geändert werden
    private void setAppearance(boolean dark) {
        if (dark) {

            setBackground(Color.darkGray);
            jPanChart.setBackground(Color.darkGray);
            jPanStatus.setBackground(Color.darkGray);
            jPanTools.setBackground(Color.darkGray);

            jLabelDevice.setForeground(Color.white);

            jtfStatus.setBackground(Color.darkGray);
            jtfStatus.setForeground(Color.white);
        } else {
            setBackground(Color.white);
            jPanChart.setBackground(Color.white);
            jPanStatus.setBackground(Color.white);
            jPanTools.setBackground(Color.white);

            jLabelDevice.setForeground(Color.black);

            jtfStatus.setBackground(Color.white);
            jtfStatus.setForeground(Color.black);
        }
    }

    //File-Methods
    private void save() throws IOException, Exception {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Bike-Datei (*.bes)", "bes"));

        File home;
        File folder;

        try {
            home = new File(System.getProperty("user.home"));
        } catch (Exception e) {
            home = null;
        }

        if (home != null && home.exists()) {
            folder = new File(home + File.separator + "Bike-Files");
            if (!folder.exists()) {
                if (!folder.mkdir()) {
                    throw new Exception("Internal Error");
                }
            }
            file = new File(folder + File.separator + Bike.getInstance().getVehicleName() + ".bes");
        } else {
            file = new File(Bike.getInstance().getVehicleName() + ".bes");
        }
        chooser.setSelectedFile(file);

        int rv = chooser.showSaveDialog(this);
        if (rv == JFileChooser.APPROVE_OPTION) {
            file = chooser.getSelectedFile();
            if (!file.getName().contains(".bes")) {
                userLogPane("Dies ist keine BES-Datei", LogLevel.WARNING);
            }

            try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
                Bike.getInstance().writeFile(w);
            } catch (Exception ex) {
                LOG.severe(ex);
            }
        }

    }

    // Saves the Communication Log
    private void saveComm() throws Exception {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Text-Datei (*.txt)", "txt"));

        File comfile = null;
        File home;
        File folder;
        Date date = Calendar.getInstance().getTime();
        DateFormat df = new SimpleDateFormat("yy.mm.DD-HH:mm:ss");

        try {
            home = new File(System.getProperty("user.home"));
        } catch (Exception e) {
            home = null;
        }

        if (home != null && home.exists()) {
            folder = new File(home + File.separator + "Bike-Files" + File.separator + "Service_Files");
            if (!folder.exists()) {
                if (!folder.mkdir()) {
                    throw new Exception("Internal Error");
                }
            }
            comfile = new File(folder + File.separator + "CommLog_" + df.format(date) +".txt");
        }

        chooser.setSelectedFile(comfile);

        int rv = chooser.showSaveDialog(this);
        if (rv == JFileChooser.APPROVE_OPTION) {
            comfile = chooser.getSelectedFile();
            
            try (BufferedWriter w = new BufferedWriter(new FileWriter(comfile))) {
                CommunicationLogger.getInstance().writeFile(w);
            } catch (Exception ex) {
                LOG.severe(ex);
            }
        }
    }

    private void open() throws FileNotFoundException, IOException, Exception {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Bike-Datei (*.bes)", "bes"));

        File home;
        File folder;

        try {
            home = new File(System.getProperty("user.home"));
        } catch (Exception e) {
            home = null;
        }

        if (home != null && home.exists()) {
            folder = new File(home + File.separator + "Bike-Files");
            if (!folder.exists()) {
                if (!folder.mkdir()) {
                    LOG.severe("Internal Error");
                }
            }
            file = new File(folder + File.separator + Bike.getInstance().getVehicleName() + ".bes");
        } else {
            file = new File(Bike.getInstance().getVehicleName() + ".bes");
        }
        chooser.setSelectedFile(file);

        int rv = chooser.showOpenDialog(this);
        if (rv == JFileChooser.APPROVE_OPTION) {
            file = chooser.getSelectedFile();
            if (!file.getName().contains(".bes")) {
                userLogPane("Dies ist keine BES-Datei", LogLevel.WARNING);
            }
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                Bike.getInstance().readFile(r);
            } catch (Exception ex) {
                userLog(ex, "Fehler beim Einlesen der Datei", LogLevel.WARNING);
            }
        }
    }

    //Config
    private void loadConfig() throws FileNotFoundException, IOException, Exception {
        File home;
        File folder;
        File ConfigFile;

        try {
            home = new File(System.getProperty("user.home"));
        } catch (Exception e) {
            home = null;
        }

        if (home != null && home.exists()) {
            folder = new File(home + File.separator + ".Bike");
            if (!folder.exists()) {
                if (!folder.mkdir()) {
                    throw new Exception("Internal Error");
                }
            }
            ConfigFile = new File(folder + File.separator + "Bike.config");
        } else {
            ConfigFile = new File("Bike.config");
        }

        if (ConfigFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(ConfigFile))) {
                Config.getInstance().readConfig(r);

            }
        }
    }

    //Getter
    public MyTelegram getTelegram() {
        return telegram;
    }

    public boolean isDark() {
        return dark;
    }
    
    public boolean addPendingRequest(Request request) {
        return pendingRequests.add(request);
    }
    
    public boolean removePendingReuest(Request request) {
        return pendingRequests.remove(request);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        jSlider = new javax.swing.JSlider();
        jPanChart = new javax.swing.JPanel();
        jPanStatus = new javax.swing.JPanel();
        jtfStatus = new javax.swing.JTextField();
        jpbStatus = new javax.swing.JProgressBar();
        jbutStartSim = new javax.swing.JButton();
        jPanTools = new javax.swing.JPanel();
        jLabelDevice = new javax.swing.JLabel();
        jcbSerialDevices = new javax.swing.JComboBox<>();
        jbutConnect = new javax.swing.JButton();
        jbutDisconnect = new javax.swing.JButton();
        jbutRefresh = new javax.swing.JButton();
        jMenuBar = new javax.swing.JMenuBar();
        jmenuFile = new javax.swing.JMenu();
        jmiOpen = new javax.swing.JMenuItem();
        jmiSave = new javax.swing.JMenuItem();
        jmiExport = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        jmiPrint = new javax.swing.JMenuItem();
        jSeparator2 = new javax.swing.JPopupMenu.Separator();
        jmiSettings = new javax.swing.JMenuItem();
        jmiQuit = new javax.swing.JMenuItem();
        jmenuSimulation = new javax.swing.JMenu();
        jmiStartSim = new javax.swing.JMenuItem();
        jSeparator3 = new javax.swing.JPopupMenu.Separator();
        jmiRefresh = new javax.swing.JMenuItem();
        jmiConnect = new javax.swing.JMenuItem();
        jmiDisconnect = new javax.swing.JMenuItem();
        jmenuAppearance = new javax.swing.JMenu();
        jcbmiDarkMode = new javax.swing.JCheckBoxMenuItem();
        jmenuDeveloper = new javax.swing.JMenu();
        jmiLogComm = new javax.swing.JCheckBoxMenuItem();
        jmiLoggedComm = new javax.swing.JMenuItem();
        jmiTestComm = new javax.swing.JMenuItem();
        jSeparator4 = new javax.swing.JPopupMenu.Separator();
        jmiReset = new javax.swing.JMenuItem();
        jmenuAbout = new javax.swing.JMenu();
        jmiAbout = new javax.swing.JMenuItem();
        jmiHelp = new javax.swing.JMenuItem();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setBackground(new java.awt.Color(255, 255, 255));

        jPanChart.setBackground(new java.awt.Color(255, 255, 255));

        javax.swing.GroupLayout jPanChartLayout = new javax.swing.GroupLayout(jPanChart);
        jPanChart.setLayout(jPanChartLayout);
        jPanChartLayout.setHorizontalGroup(
            jPanChartLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 611, Short.MAX_VALUE)
        );
        jPanChartLayout.setVerticalGroup(
            jPanChartLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 281, Short.MAX_VALUE)
        );

        getContentPane().add(jPanChart, java.awt.BorderLayout.CENTER);

        jPanStatus.setBackground(new java.awt.Color(255, 255, 255));
        jPanStatus.setLayout(new java.awt.GridBagLayout());
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanStatus.add(jtfStatus, gridBagConstraints);

        jpbStatus.setBorder(null);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanStatus.add(jpbStatus, gridBagConstraints);

        jbutStartSim.setText("Start Simulation");
        jbutStartSim.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbutStartSimActionPerformed(evt);
            }
        });
        jPanStatus.add(jbutStartSim, new java.awt.GridBagConstraints());

        getContentPane().add(jPanStatus, java.awt.BorderLayout.PAGE_END);

        jPanTools.setBackground(new java.awt.Color(255, 255, 255));
        jPanTools.setLayout(new java.awt.GridBagLayout());

        jLabelDevice.setText("Gerät wählen: ");
        jLabelDevice.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 5));
        jPanTools.add(jLabelDevice, new java.awt.GridBagConstraints());

        jcbSerialDevices.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        jPanTools.add(jcbSerialDevices, gridBagConstraints);

        jbutConnect.setText("Verbinden");
        jbutConnect.setPreferredSize(new java.awt.Dimension(127, 29));
        jbutConnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbutConnectActionPerformed(evt);
            }
        });
        jPanTools.add(jbutConnect, new java.awt.GridBagConstraints());

        jbutDisconnect.setText("Trennen");
        jbutDisconnect.setPreferredSize(new java.awt.Dimension(127, 29));
        jbutDisconnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbutDisconnectActionPerformed(evt);
            }
        });
        jPanTools.add(jbutDisconnect, new java.awt.GridBagConstraints());

        jbutRefresh.setText("Aktualisieren");
        jbutRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jbutRefreshActionPerformed(evt);
            }
        });
        jPanTools.add(jbutRefresh, new java.awt.GridBagConstraints());

        getContentPane().add(jPanTools, java.awt.BorderLayout.PAGE_START);

        jmenuFile.setText("Datei");

        jmiOpen.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.event.InputEvent.META_MASK));
        jmiOpen.setText("Öffnen");
        jmiOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiOpenActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiOpen);

        jmiSave.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.META_MASK));
        jmiSave.setText("Speichern");
        jmiSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiSaveActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiSave);

        jmiExport.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E, java.awt.event.InputEvent.META_MASK));
        jmiExport.setText("Exportieren");
        jmiExport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiExportActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiExport);
        jmenuFile.add(jSeparator1);

        jmiPrint.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P, java.awt.event.InputEvent.META_MASK));
        jmiPrint.setText("Drucken");
        jmiPrint.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiPrintActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiPrint);
        jmenuFile.add(jSeparator2);

        jmiSettings.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_COMMA, java.awt.event.InputEvent.META_MASK));
        jmiSettings.setText("Einstellungen");
        jmiSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiSettingsActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiSettings);

        jmiQuit.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Q, java.awt.event.InputEvent.META_MASK));
        jmiQuit.setText("Beenden");
        jmiQuit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiQuitActionPerformed(evt);
            }
        });
        jmenuFile.add(jmiQuit);

        jMenuBar.add(jmenuFile);

        jmenuSimulation.setText("Simulation");

        jmiStartSim.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.META_MASK));
        jmiStartSim.setText("Start Simulation");
        jmiStartSim.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiStartSimActionPerformed(evt);
            }
        });
        jmenuSimulation.add(jmiStartSim);
        jmenuSimulation.add(jSeparator3);

        jmiRefresh.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_U, java.awt.event.InputEvent.META_MASK));
        jmiRefresh.setText("Aktualisieren");
        jmiRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiRefreshActionPerformed(evt);
            }
        });
        jmenuSimulation.add(jmiRefresh);

        jmiConnect.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.META_MASK));
        jmiConnect.setText("Verbinden");
        jmiConnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiConnectActionPerformed(evt);
            }
        });
        jmenuSimulation.add(jmiConnect);

        jmiDisconnect.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.InputEvent.META_MASK));
        jmiDisconnect.setText("Trennen");
        jmiDisconnect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiDisconnectActionPerformed(evt);
            }
        });
        jmenuSimulation.add(jmiDisconnect);

        jMenuBar.add(jmenuSimulation);

        jmenuAppearance.setText("Darstellung");

        jcbmiDarkMode.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.META_MASK));
        jcbmiDarkMode.setSelected(true);
        jcbmiDarkMode.setText("Dark Mode");
        jcbmiDarkMode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jcbmiDarkModeActionPerformed(evt);
            }
        });
        jmenuAppearance.add(jcbmiDarkMode);

        jMenuBar.add(jmenuAppearance);

        jmenuDeveloper.setText("Entwicklungstools");

        jmiLogComm.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.META_MASK));
        jmiLogComm.setSelected(true);
        jmiLogComm.setText("Kommunikation protokollieren");
        jmiLogComm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiLogCommActionPerformed(evt);
            }
        });
        jmenuDeveloper.add(jmiLogComm);

        jmiLoggedComm.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.META_MASK));
        jmiLoggedComm.setText("Kommunikationsprotokoll");
        jmiLoggedComm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiLoggedCommActionPerformed(evt);
            }
        });
        jmenuDeveloper.add(jmiLoggedComm);

        jmiTestComm.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.ALT_MASK | java.awt.event.InputEvent.META_MASK));
        jmiTestComm.setText("Kommunikation testen");
        jmiTestComm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiTestCommActionPerformed(evt);
            }
        });
        jmenuDeveloper.add(jmiTestComm);
        jmenuDeveloper.add(jSeparator4);

        jmiReset.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.SHIFT_MASK | java.awt.event.InputEvent.META_MASK));
        jmiReset.setText("Reset Arduino");
        jmiReset.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiResetActionPerformed(evt);
            }
        });
        jmenuDeveloper.add(jmiReset);

        jMenuBar.add(jmenuDeveloper);

        jmenuAbout.setText("Über");

        jmiAbout.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PERIOD, java.awt.event.InputEvent.META_MASK));
        jmiAbout.setText("Über");
        jmiAbout.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jmiAboutActionPerformed(evt);
            }
        });
        jmenuAbout.add(jmiAbout);

        jmiHelp.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0));
        jmiHelp.setText("Hilfe");
        jmiHelp.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                onHelp(evt);
            }
        });
        jmenuAbout.add(jmiHelp);

        jMenuBar.add(jmenuAbout);

        setJMenuBar(jMenuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jmiSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiSaveActionPerformed
        try {
            save();
            userLog("Datei erfolgreich gespeichert", LogLevel.FINE);
        } catch (Exception ex) {
            userLog(ex, "Fehler beim Speichern der Datei", LogLevel.WARNING);
        }
    }//GEN-LAST:event_jmiSaveActionPerformed

    private void jmiPrintActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiPrintActionPerformed

    }//GEN-LAST:event_jmiPrintActionPerformed

    private void jmiSettingsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiSettingsActionPerformed
        settings.setAppearance(dark);
        settings.setVisible(true);

        if (settings.isPressedOK()) {
            dark = settings.isDark();
            setAppearance(dark);
        }
    }//GEN-LAST:event_jmiSettingsActionPerformed

    private void jmiQuitActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiQuitActionPerformed

    }//GEN-LAST:event_jmiQuitActionPerformed

    private void jmiStartSimActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiStartSimActionPerformed
        vehicle.setAppearance(dark);
        vehicle.setVisible(true);

        LOG.info("Simulation started");

        if (vehicle.isPressedOK()) {
            measure.setAppearance(dark);
            measure.setVisible(true);
        }
    }//GEN-LAST:event_jmiStartSimActionPerformed

    private void jmiRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiRefreshActionPerformed
        refreshPorts();
        LOG.info("Ports refreshed");
    }//GEN-LAST:event_jmiRefreshActionPerformed

    private void jmiConnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiConnectActionPerformed
        try {
            MyConnectPortWorker w = new MyConnectPortWorker((String) jcbSerialDevices.getSelectedItem());
            w.execute();
            jtfStatus.setText("Port erfolgreich geöffnet");
            activeWorker = w;
            refreshGui();
            userLog("Connected with " + jcbSerialDevices.getSelectedItem(), LogLevel.FINE);
        } catch (Throwable ex) {
            userLog(ex, "Fehler beim Verbinden", LogLevel.SEVERE);
        }
    }//GEN-LAST:event_jmiConnectActionPerformed

    private void jmiDisconnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiDisconnectActionPerformed
        try {
            port.closePort();
            jtfStatus.setText("Port erfolgreich geschlossen");
        } catch (Exception e) {
            LOG.warning(e);
            jtfStatus.setText("Fehler beim Schließen des Ports");
        } finally {
            port = null;
            try {
                telegram.setSerialPort(null);
            } catch (SerialPortException ex) {
                LOG.severe(ex);
            }
            refreshGui();
        }
    }//GEN-LAST:event_jmiDisconnectActionPerformed

    private void jmiAboutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiAboutActionPerformed
        about.setAppearance(dark);
        about.setVisible(true);
        if (port != null) {
            about.writeDevice(port.getPortName());
        } else {
            about.writeDevice("Kein Prüfstand verbunden...");
        }
    }//GEN-LAST:event_jmiAboutActionPerformed

    private void jbutConnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbutConnectActionPerformed
        jmiConnectActionPerformed(evt);
    }//GEN-LAST:event_jbutConnectActionPerformed

    private void jbutDisconnectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbutDisconnectActionPerformed
        jmiDisconnectActionPerformed(evt);
    }//GEN-LAST:event_jbutDisconnectActionPerformed

    private void jbutRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbutRefreshActionPerformed
        refreshPorts();
        userLog("Port-Liste aktualisiert", LogLevel.INFO);
    }//GEN-LAST:event_jbutRefreshActionPerformed

    private void onHelp(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_onHelp
        help.setAppearance(dark);
        help.setVisible(true);
    }//GEN-LAST:event_onHelp

    private void jbutStartSimActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jbutStartSimActionPerformed
        vehicle.setAppearance(dark);
        vehicle.setVisible(true);

        LOG.info("Simulation started");

        if (vehicle.isPressedOK()) {
            measure.setAppearance(dark);
            measure.setVisible(true);
        }
    }//GEN-LAST:event_jbutStartSimActionPerformed

    private void jcbmiDarkModeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jcbmiDarkModeActionPerformed
        dark = jcbmiDarkMode.getState();
        setAppearance(dark);
        Config.getInstance().setDark(dark);
        try {
            settings.saveConfig(Config.getInstance());
        } catch (Exception e) {
            userLog(e, "Fehler beim Speichern der Config-File", LogLevel.WARNING);
        }
    }//GEN-LAST:event_jcbmiDarkModeActionPerformed

    private void jmiExportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiExportActionPerformed

    }//GEN-LAST:event_jmiExportActionPerformed

    private void jmiOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiOpenActionPerformed
        try {
            open();
            userLog("Datei erfolgreich geöffnet", LogLevel.FINE);
        } catch (Exception ex) {
            userLog(ex, "Fehler beim Öffnen der Datei", LogLevel.WARNING);
        }
    }//GEN-LAST:event_jmiOpenActionPerformed

    private void jmiTestCommActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiTestCommActionPerformed
        try {
            pendingRequests.add(telegram.init());
        } catch (Exception e) {
            userLog(e, "TestComm: Fehler!", LogLevel.SEVERE);
        }
    }//GEN-LAST:event_jmiTestCommActionPerformed

    private void jmiResetActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiResetActionPerformed
        try {
            pendingRequests.add(telegram.reset());
            jtfStatus.setText("Reset...");
        } catch (Exception e) {
            LOG.warning(e);
            jtfStatus.setText("Fehler beim Reset");
        } finally {
            refreshGui();
        }

    }//GEN-LAST:event_jmiResetActionPerformed

    private void jmiLogCommActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiLogCommActionPerformed
        CommunicationLogger.getInstance().setCommLogging(jmiLogComm.getState());
    }//GEN-LAST:event_jmiLogCommActionPerformed

    private void jmiLoggedCommActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jmiLoggedCommActionPerformed
        try {
            saveComm();
        } catch (Exception ex) {
            userLog(ex, "Fehler beim Speichern des Kommunikationsprotokolls", LogLevel.WARNING);
        }
    }//GEN-LAST:event_jmiLoggedCommActionPerformed

    private class MyConnectPortWorker extends ConnectPortWorker {

        public MyConnectPortWorker(String port) {
            super(port);
        }

        @Override
        protected void done() {
            try {
                port = (jssc.SerialPort) get(2, TimeUnit.SECONDS);
                telegram.setSerialPort(port);
            } catch (Exception e) {
                LOG.warning(e);
                jtfStatus.setText("Port konnte nicht geöffnet werden...");
            } finally {
                activeWorker = null;
                refreshGui();
            }
        }

    }

    public class MyTelegram extends Telegram {

        @Override
        protected void done() {

        }

        @Override
        protected void process(List<Request> chunks) {
            for (Request r : chunks) {
                if (r.getStatus() == Status.DONE) {
                    jtfStatus.setText("OK");
                } else if (r.getStatus() == Status.ERROR) {
                    jtfStatus.setText("ERROR");
                } else {
                    continue;
                }
                if (!pendingRequests.remove(r)) {
                    LOG.warning("peningRequests: Objekt nicht vorhanden...");

                }
            }
        }
    }

    /**
     * @param args the command line arguments
     * @throws javax.swing.UnsupportedLookAndFeelException
     */
    public static void main(String args[]) throws UnsupportedLookAndFeelException {
        LOGP.addHandler(new LogBackgroundHandler(new LogOutputStreamHandler(System.out)));
        LOG.info("Start of BESDyno");

        Config.initInstance(new File(System.getProperty("user.home") + System.getProperty("file.separator") + "config.json"));
        Bike.getInstance();

        //Menu-Bar support for macOS
        if (System.getProperty("os.name").contains("Mac OS X")) {
            try {
                System.setProperty("apple.laf.useScreenMenuBar", "true");
                System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Zweiradprüfstand");
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex) {
                java.util.logging.Logger.getLogger(BESDyno.class
                        .getName()).log(java.util.logging.Level.SEVERE, null, ex);
                //LOG.severe(ex);
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                new BESDyno().setVisible(true);
            });
            //Other OS
        } else {
            try {
                for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        javax.swing.UIManager.setLookAndFeel(info.getClassName());
                        break;

                    }
                }
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ex) {
                java.util.logging.Logger.getLogger(BESDyno.class
                        .getName()).log(java.util.logging.Level.SEVERE, null, ex);
            }

            java.awt.EventQueue.invokeLater(() -> {
                new BESDyno().setVisible(true);
            });
        }
    }

    static {
        //System.setProperty("logging.Logger.printStackTrace", "");
        System.setProperty("logging.LogOutputStreamHandler.showRecordHashcode", "false");
        //System.setProperty("logging.Logger.printAll", "");
        //System.setProperty("logging.LogRecordDataFormattedText.Terminal","NETBEANS");
        System.setProperty("logging.LogRecordDataFormattedText.Terminal", "LINUX");
        System.setProperty("logging.Logger.Level", "INFO");
        //System.setProperty("Test1.Logger.Level", "ALL");
        System.setProperty("test.Test.Logger.Level", "FINER");
        System.setProperty("test.*.Logger.Level", "FINE");
        //System.setProperty("test.*.Logger.Handlers", "test.MyHandler");
        //System.setProperty("test.*.Logger.Filter", "test.MyFilter");
        //System.setProperty("logging.LogOutputStreamHandler.colorize", "false");

        LOG
                = Logger.getLogger(BESDyno.class
                        .getName());
        LOGP = Logger.getParentLogger();
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel jLabelDevice;
    private javax.swing.JMenuBar jMenuBar;
    private javax.swing.JPanel jPanChart;
    private javax.swing.JPanel jPanStatus;
    private javax.swing.JPanel jPanTools;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JPopupMenu.Separator jSeparator2;
    private javax.swing.JPopupMenu.Separator jSeparator3;
    private javax.swing.JPopupMenu.Separator jSeparator4;
    private javax.swing.JSlider jSlider;
    private javax.swing.JButton jbutConnect;
    private javax.swing.JButton jbutDisconnect;
    private javax.swing.JButton jbutRefresh;
    private javax.swing.JButton jbutStartSim;
    private javax.swing.JComboBox<String> jcbSerialDevices;
    private javax.swing.JCheckBoxMenuItem jcbmiDarkMode;
    private javax.swing.JMenu jmenuAbout;
    private javax.swing.JMenu jmenuAppearance;
    private javax.swing.JMenu jmenuDeveloper;
    private javax.swing.JMenu jmenuFile;
    private javax.swing.JMenu jmenuSimulation;
    private javax.swing.JMenuItem jmiAbout;
    private javax.swing.JMenuItem jmiConnect;
    private javax.swing.JMenuItem jmiDisconnect;
    private javax.swing.JMenuItem jmiExport;
    private javax.swing.JMenuItem jmiHelp;
    private javax.swing.JCheckBoxMenuItem jmiLogComm;
    private javax.swing.JMenuItem jmiLoggedComm;
    private javax.swing.JMenuItem jmiOpen;
    private javax.swing.JMenuItem jmiPrint;
    private javax.swing.JMenuItem jmiQuit;
    private javax.swing.JMenuItem jmiRefresh;
    private javax.swing.JMenuItem jmiReset;
    private javax.swing.JMenuItem jmiSave;
    private javax.swing.JMenuItem jmiSettings;
    private javax.swing.JMenuItem jmiStartSim;
    private javax.swing.JMenuItem jmiTestComm;
    private javax.swing.JProgressBar jpbStatus;
    private javax.swing.JTextField jtfStatus;
    // End of variables declaration//GEN-END:variables
}
