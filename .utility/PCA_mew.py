import pandas as pd
import numpy as np
from sklearn.decomposition import PCA
from sklearn.preprocessing import StandardScaler
from sklearn.neighbors import KNeighborsRegressor
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error
from sklearn.feature_selection import mutual_info_regression
from sklearn.cluster import KMeans

import matplotlib.pyplot as plt
# Axes3D import is not needed as it's automatically imported with plt
import seaborn as sns

def create_combined_features(df):
    """Crea nuove feature combinate"""
    df_enhanced = df.copy()
    
    # Feature di velocità totale
    df_enhanced['speed_total'] = np.sqrt(df_enhanced['speedX']**2 + 
                                       df_enhanced['speedY']**2 + 
                                       df_enhanced['speedZ']**2)
    
    # Feature di accelerazione (variazione di velocità)
    df_enhanced['speed_change'] = df_enhanced['speed_total'].diff().fillna(0)
    
    # Rapporto RPM/gear per efficienza motore
    df_enhanced['rpm_gear_ratio'] = df_enhanced['rpm'] / (df_enhanced['gear'] + 1)
    
    # Media dei sensori di traccia per posizione generale
    track_cols = [f'track{i}' for i in range(19)]
    df_enhanced['track_mean'] = df_enhanced[track_cols].mean(axis=1)
    df_enhanced['track_std'] = df_enhanced[track_cols].std(axis=1)
    
    # Indice di rischio (combinazione di velocità, danno e posizione)
    df_enhanced['risk_index'] = (df_enhanced['speed_total'] * df_enhanced['damage']) / (df_enhanced['fuel'] + 1)
    
    # Efficienza carburante
    df_enhanced['fuel_efficiency'] = df_enhanced['distanceFromStart'] / (100 - df_enhanced['fuel'] + 1)
    
    # Sensori laterali vs centrali
    df_enhanced['side_sensors'] = (df_enhanced['track0'] + df_enhanced['track1'] + 
                                 df_enhanced['track17'] + df_enhanced['track18']) / 4
    df_enhanced['center_sensors'] = (df_enhanced['track8'] + df_enhanced['track9'] + 
                                   df_enhanced['track10']) / 3
    
    # Stabilità ruote
    wheel_cols = ['wheel0', 'wheel1', 'wheel2', 'wheel3']
    df_enhanced['wheel_stability'] = df_enhanced[wheel_cols].std(axis=1)
    
    return df_enhanced

def plot_3d_visualizations(X_pca, y, pca, input_features):
    """Crea visualizzazioni 3D"""
    fig = plt.figure(figsize=(20, 15))
    
    # 1. PCA 3D scatter plot colorato per steer
    ax1 = fig.add_subplot(2, 3, 1, projection='3d')
    scatter = ax1.scatter(X_pca[:, 0], X_pca[:, 1], X_pca[:, 2], 
                         c=y['steer'], cmap='viridis', alpha=0.6)
    ax1.set_xlabel('PC1')
    ax1.set_ylabel('PC2')
    ax1.set_zlabel('PC3')
    ax1.set_title('PCA 3D - Colorato per Steer')
    plt.colorbar(scatter, ax=ax1, shrink=0.5)
    
    # 2. PCA 3D scatter plot colorato per accel
    ax2 = fig.add_subplot(2, 3, 2, projection='3d')
    scatter2 = ax2.scatter(X_pca[:, 0], X_pca[:, 1], X_pca[:, 2], 
                          c=y['accel'], cmap='plasma', alpha=0.6)
    ax2.set_xlabel('PC1')
    ax2.set_ylabel('PC2')
    ax2.set_zlabel('PC3')
    ax2.set_title('PCA 3D - Colorato per Accel')
    plt.colorbar(scatter2, ax=ax2, shrink=0.5)
    
    # 3. PCA 3D scatter plot colorato per brake
    ax3 = fig.add_subplot(2, 3, 3, projection='3d')
    scatter3 = ax3.scatter(X_pca[:, 0], X_pca[:, 1], X_pca[:, 2], 
                          c=y['brake'], cmap='Reds', alpha=0.6)
    ax3.set_xlabel('PC1')
    ax3.set_ylabel('PC2')
    ax3.set_zlabel('PC3')
    ax3.set_title('PCA 3D - Colorato per Brake')
    plt.colorbar(scatter3, ax=ax3, shrink=0.5)
    
    # 4. Clustering K-means su spazio PCA
    kmeans = KMeans(n_clusters=5, random_state=42)
    clusters = kmeans.fit_predict(X_pca[:, :3])
    
    ax4 = fig.add_subplot(2, 3, 4, projection='3d')
    ax4.scatter(X_pca[:, 0], X_pca[:, 1], X_pca[:, 2], 
                c=clusters, cmap='tab10', alpha=0.6)
    ax4.set_xlabel('PC1')
    ax4.set_ylabel('PC2')
    ax4.set_zlabel('PC3')
    ax4.set_title('K-means Clustering (5 clusters)')
    
    # 5. Contribuzione delle feature originali alle prime 3 PC
    ax5 = fig.add_subplot(2, 3, 5)
    feature_contrib = pca.components_[:3].T
    im = ax5.imshow(feature_contrib, cmap='RdBu', aspect='auto')
    ax5.set_xticks(range(3))
    ax5.set_xticklabels(['PC1', 'PC2', 'PC3'])
    ax5.set_yticks(range(len(input_features)))
    ax5.set_yticklabels(input_features, fontsize=8)
    ax5.set_title('Contribuzione Features alle PC')
    plt.colorbar(im, ax=ax5)
    
    # 6. Proiezione delle azioni nello spazio PCA
    ax6 = fig.add_subplot(2, 3, 6, projection='3d')
    # Colora in base alla combinazione di azioni
    action_combo = y['steer'].abs() + y['accel'] + y['brake']
    scatter6 = ax6.scatter(X_pca[:, 0], X_pca[:, 1], X_pca[:, 2], 
                          c=action_combo, cmap='coolwarm', alpha=0.6)
    ax6.set_xlabel('PC1')
    ax6.set_ylabel('PC2')
    ax6.set_zlabel('PC3')
    ax6.set_title('PCA 3D - Intensità Azioni Combinate')
    plt.colorbar(scatter6, ax=ax6, shrink=0.5)
    
    plt.tight_layout()
    plt.savefig('pca_3d_analysis.png', dpi=300, bbox_inches='tight')
    plt.show()

def analyze_feature_interactions(df_enhanced, input_features, output_features):
    """Analizza le interazioni tra feature"""
    
    # Calcola mutual information per le nuove feature
    new_features = ['speed_total', 'speed_change', 'rpm_gear_ratio', 'track_mean', 
                   'track_std', 'risk_index', 'fuel_efficiency', 'side_sensors', 
                   'center_sensors', 'wheel_stability']
    
    all_features = input_features + new_features
    X_all = df_enhanced[all_features]
    y_all = df_enhanced[output_features]
    
    # Rimuovi valori NaN e infiniti
    X_all = X_all.replace([np.inf, -np.inf], np.nan).fillna(0)
    
    mutual_info_results = {}
    for target in output_features:
        mi_scores = mutual_info_regression(X_all, y_all[target])
        mutual_info_results[target] = pd.DataFrame({
            'feature': all_features,
            'mutual_info': mi_scores
        }).sort_values('mutual_info', ascending=False)
    
    # Visualizza mutual information
    _, axes = plt.subplots(1, 3, figsize=(18, 6))
    
    for i, target in enumerate(output_features):
        top_features = mutual_info_results[target].head(15)
        axes[i].barh(range(len(top_features)), top_features['mutual_info'])
        axes[i].set_yticks(range(len(top_features)))
        axes[i].set_yticklabels(top_features['feature'])
        axes[i].set_xlabel('Mutual Information Score')
        axes[i].set_title(f'Top Features for {target}')
        axes[i].grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig('mutual_information_analysis.png', dpi=300, bbox_inches='tight')
    plt.show()
    
    return mutual_info_results

def create_correlation_heatmap(df_enhanced, input_features, output_features):
    """Crea heatmap delle correlazioni"""
    new_features = ['speed_total', 'speed_change', 'rpm_gear_ratio', 'track_mean', 
                   'track_std', 'risk_index', 'fuel_efficiency', 'side_sensors', 
                   'center_sensors', 'wheel_stability']
    
    all_features = input_features + new_features + output_features
    correlation_matrix = df_enhanced[all_features].corr()
    
    plt.figure(figsize=(16, 14))
    mask = np.triu(np.ones_like(correlation_matrix, dtype=bool))
    sns.heatmap(correlation_matrix, mask=mask, annot=False, cmap='RdBu_r', 
                center=0, square=True, linewidths=0.5)
    plt.title('Matrice di Correlazione Features (incluse quelle combinate)')
    plt.tight_layout()
    plt.savefig('correlation_heatmap.png', dpi=300, bbox_inches='tight')
    plt.show()
    
    # Focus sulle correlazioni con le variabili target
    target_correlations = correlation_matrix[output_features].drop(output_features)
    
    plt.figure(figsize=(12, 8))
    sns.heatmap(target_correlations, annot=True, cmap='RdBu_r', center=0, 
                fmt='.3f', square=False)
    plt.title('Correlazioni tra Features e Variabili Target')
    plt.tight_layout()
    plt.savefig('target_correlations.png', dpi=300, bbox_inches='tight')
    plt.show()
    
    return correlation_matrix

def analyze_features_for_behavioral_cloning():
    # Carica il dataset
    df = pd.read_csv("classes/dataset_union.csv")
    df = df.sample(frac=0.5, random_state=42)
    # Crea feature combinate
    df_enhanced = create_combined_features(df)
    
    # Definisci le features di input e output per behavioral cloning
    input_features = ['time', 'angle', 'curLapTime', 'distanceFromStart', 'fuel', 'damage', 
                     'gear', 'rpm', 'speedX', 'speedY', 'speedZ', 'lastLapTime',
                     'track0', 'track1', 'track2', 'track3', 'track4', 'track5', 
                     'track6', 'track7', 'track8', 'track9', 'track10', 'track11', 
                     'track12', 'track13', 'track14', 'track15', 'track16', 'track17', 
                     'track18', 'wheel0', 'wheel1', 'wheel2', 'wheel3', 'trackPos']
    
    output_features = ['steer', 'accel', 'brake']
    
    # Rimuovi righe con valori mancanti
    df_clean = df_enhanced.dropna()
    
    X = df_clean[input_features]
    y = df_clean[output_features]
    
    # Standardizza le features
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)
    
    # Applica PCA
    pca = PCA()
    X_pca = pca.fit_transform(X_scaled)
    
    # Analisi della varianza (codice originale)
    cumulative_variance = np.cumsum(pca.explained_variance_ratio_)
    n_components_95 = np.argmax(cumulative_variance >= 0.95) + 1
    print(f"Componenti necessarie per 95% varianza: {n_components_95}")
    
    # NUOVE VISUALIZZAZIONI
    
    # 1. Visualizzazioni 3D
    #print("Creando visualizzazioni 3D...")
    #plot_3d_visualizations(X_pca, y, pca, input_features)
    
    # 2. Analisi delle correlazioni
    print("Analizzando correlazioni...")
    correlation_matrix = create_correlation_heatmap(df_enhanced, input_features, output_features)
    
    # 3. Analisi mutual information con feature combinate
    print("Analizzando feature interactions...")
    mutual_info_results = analyze_feature_interactions(df_enhanced, input_features, output_features)
    
    # 4. Confronto performance con feature combinate
    new_features = ['speed_total', 'speed_change', 'rpm_gear_ratio', 'track_mean', 
                   'track_std', 'risk_index', 'fuel_efficiency', 'side_sensors', 
                   'center_sensors', 'wheel_stability']
    
    # Test con feature combinate
    enhanced_features = input_features + new_features
    X_enhanced = df_clean[enhanced_features].replace([np.inf, -np.inf], np.nan).fillna(0)
    X_enhanced_scaled = StandardScaler().fit_transform(X_enhanced)
    
    # Separate training/test for original features
    X_original = df_clean[input_features]
    X_original_scaled = StandardScaler().fit_transform(X_original)
    
    X_train_orig, X_test_orig, y_train_orig, y_test_orig = train_test_split(
        X_original_scaled, y, test_size=0.2, random_state=42)
    
    X_train_enh, X_test_enh, y_train_enh, y_test_enh = train_test_split(
        X_enhanced_scaled, y, test_size=0.2, random_state=42)
    
    # Train separate models for fair comparison
    knn_original = KNeighborsRegressor(n_neighbors=5)
    knn_original.fit(X_train_orig, y_train_orig)
    y_pred_original = knn_original.predict(X_test_orig)
    mse_original = mean_squared_error(y_test_orig, y_pred_original)
    
    knn_enhanced = KNeighborsRegressor(n_neighbors=5)
    knn_enhanced.fit(X_train_enh, y_train_enh)
    y_pred_enhanced = knn_enhanced.predict(X_test_enh)
    mse_enhanced = mean_squared_error(y_test_enh, y_pred_enhanced)
    
    print(f"\nPerformance con feature combinate:")
    print(f"MSE con feature originali: {mse_original:.6f}")
    print(f"MSE con feature combinate: {mse_enhanced:.6f}")
    
    # Stampa le migliori feature combinate per ogni target
    print("\nMigliori feature per ogni target (Mutual Information):")
    for target in output_features:
        print(f"\n{target.upper()}:")
        top_5 = mutual_info_results[target].head(5)
        for _, row in top_5.iterrows():
            print(f"  {row['feature']}: {row['mutual_info']:.4f}")
    
    return mutual_info_results, correlation_matrix

if __name__ == "__main__":
    mutual_info_results, correlation_matrix = analyze_features_for_behavioral_cloning()